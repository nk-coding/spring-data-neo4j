/*
 * Copyright 2011-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.neo4j.core.mapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.driver.internal.value.NullValue;
import org.neo4j.driver.types.Entity;
import org.neo4j.driver.types.MapAccessor;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.neo4j.driver.types.Type;
import org.neo4j.driver.types.TypeSystem;
import org.springframework.core.CollectionFactory;
import org.springframework.core.KotlinDetector;
import org.springframework.data.mapping.AssociationHandler;
import org.springframework.data.mapping.MappingException;
import org.springframework.data.mapping.Parameter;
import org.springframework.data.mapping.PersistentPropertyAccessor;
import org.springframework.data.mapping.PropertyHandler;
import org.springframework.data.mapping.model.EntityInstantiators;
import org.springframework.data.mapping.model.ParameterValueProvider;
import org.springframework.data.neo4j.core.convert.Neo4jConversionService;
import org.springframework.data.neo4j.core.mapping.callback.EventSupport;
import org.springframework.data.neo4j.core.schema.TargetNode;
import org.springframework.data.util.ReflectionUtils;
import org.springframework.data.util.TypeInformation;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * @author Michael J. Simons
 * @author Gerrit Meier
 * @author Philipp Tölle
 * @soundtrack The Kleptones - A Night At The Hip-Hopera
 * @since 6.0
 */
final class DefaultNeo4jEntityConverter implements Neo4jEntityConverter {

	private final EntityInstantiators entityInstantiators;
	private final NodeDescriptionStore nodeDescriptionStore;
	private final Neo4jConversionService conversionService;

	private final EventSupport eventSupport;

	private final KnownObjects knownObjects = new KnownObjects();

	private final Type nodeType;
	private final Type relationshipType;
	private final Type mapType;
	private final Type listType;
	private final Map<String, Collection<Node>> labelNodeCache = new HashMap<>();

	DefaultNeo4jEntityConverter(EntityInstantiators entityInstantiators, NodeDescriptionStore nodeDescriptionStore,
			Neo4jConversionService conversionService, EventSupport eventSupport, TypeSystem typeSystem) {

		Assert.notNull(entityInstantiators, "EntityInstantiators must not be null");
		Assert.notNull(conversionService, "Neo4jConversionService must not be null");
		Assert.notNull(nodeDescriptionStore, "NodeDescriptionStore must not be null");
		Assert.notNull(typeSystem, "TypeSystem must not be null");

		this.entityInstantiators = entityInstantiators;
		this.conversionService = conversionService;
		this.nodeDescriptionStore = nodeDescriptionStore;
		this.eventSupport = eventSupport;

		this.nodeType = typeSystem.NODE();
		this.relationshipType = typeSystem.RELATIONSHIP();
		this.mapType = typeSystem.MAP();
		this.listType = typeSystem.LIST();
	}

	@Override
	@Nullable
	public <R> R read(Class<R> targetType, MapAccessor mapAccessor) {

		knownObjects.nextRecord();
		labelNodeCache.clear();

		@SuppressWarnings("unchecked") // ¯\_(ツ)_/¯
		Neo4jPersistentEntity<R> rootNodeDescription = (Neo4jPersistentEntity<R>) nodeDescriptionStore.getNodeDescription(targetType);
		MapAccessor queryRoot = determineQueryRoot(mapAccessor, rootNodeDescription);

		try {
			return queryRoot == null ? null : map(queryRoot, queryRoot, rootNodeDescription);
		} catch (Exception e) {
			throw new MappingException("Error mapping " + mapAccessor, e);
		}
	}

	@Nullable
	private <R> MapAccessor determineQueryRoot(MapAccessor mapAccessor, @Nullable Neo4jPersistentEntity<R> rootNodeDescription) {

		if (rootNodeDescription == null) {
			return null;
		}

		List<String> primaryLabels = new ArrayList<>();
		primaryLabels.add(rootNodeDescription.getPrimaryLabel());
		rootNodeDescription.getChildNodeDescriptionsInHierarchy().forEach(nodeDescription -> primaryLabels.add(nodeDescription.getPrimaryLabel()));

		// Massage the initial mapAccessor into something we can deal with
		Iterable<Value> recordValues = mapAccessor instanceof Value && ((Value) mapAccessor).hasType(nodeType) ?
				Collections.singletonList((Value) mapAccessor) : mapAccessor.values();

		List<Node> matchingNodes = new ArrayList<>(); // The node that eventually becomes the query root. The list should only contain one node.
		List<Node> seenMatchingNodes = new ArrayList<>(); // A list of candidates: All things that are nodes and have a matching label

		for (Value value : recordValues) {
			if (value.hasType(nodeType)) { // It is a node
				Node node = value.asNode();
				if (primaryLabels.stream().anyMatch(node::hasLabel)) { // it has a matching label
					// We haven't seen this node yet, so we take it
					if (knownObjects.getObject(node.id()) == null) {
						matchingNodes.add(node);
					} else {
						seenMatchingNodes.add(node);
					}
				}
			}
		}

		// Prefer the candidates over candidates previously seen
		List<Node> finalCandidates = matchingNodes.isEmpty() ? seenMatchingNodes : matchingNodes;

		if (finalCandidates.size() > 1) {
			throw new MappingException("More than one matching node in the record");
		} else if (!finalCandidates.isEmpty()) {
			if (mapAccessor.size() > 1) {
				return mergeRootNodeWithRecord(finalCandidates.get(0), mapAccessor);
			} else {
				return finalCandidates.get(0);
			}
		} else {
			int cnt = 0;
			Value firstValue = Values.NULL;
			for (Value value : recordValues) {
				if (cnt == 0) {
					firstValue = value;
				}
				if (value.hasType(mapType) && !(value.hasType(nodeType) || value.hasType(relationshipType))) {
					return value;
				}
				++cnt;
			}

			// Cater for results that have one single, null column. This is the case for MATCH (x) OPTIONAL MATCH (something) RETURN something
			if (cnt == 1 && firstValue.isNull()) {
				return null;
			}
		}

		throw new NoRootNodeMappingException(mapAccessor, rootNodeDescription);
	}

	private Collection<String> createDynamicLabelsProperty(TypeInformation<?> type, Collection<String> dynamicLabels) {

		Collection<String> target = CollectionFactory.createCollection(type.getType(), String.class, dynamicLabels.size());
		target.addAll(dynamicLabels);
		return target;
	}

	@Override
	public void write(Object source, Map<String, Object> parameters) {
		Map<String, Object> properties = new HashMap<>();

		Neo4jPersistentEntity<?> nodeDescription = (Neo4jPersistentEntity<?>) nodeDescriptionStore
				.getNodeDescription(source.getClass());

		PersistentPropertyAccessor<Object> propertyAccessor = nodeDescription.getPropertyAccessor(source);
		PropertyHandlerSupport.of(nodeDescription).doWithProperties((Neo4jPersistentProperty p) -> {

			// Skip the internal properties, we don't want them to end up stored as properties
			if (p.isInternalIdProperty() || p.isDynamicLabels() || p.isEntity() || p.isVersionProperty() || p.isReadOnly()) {
				return;
			}

			final Value value = conversionService.writeValue(propertyAccessor.getProperty(p), p.getTypeInformation(), p.getOptionalConverter());
			if (p.isComposite()) {
				properties.put(p.getPropertyName(), new MapValueWrapper(value));
				//value.keys().forEach(k -> properties.put(k, value.get(k)));
			} else {
				properties.put(p.getPropertyName(), value);
			}
		});

		parameters.put(Constants.NAME_OF_PROPERTIES_PARAM, properties);

		// in case of relationship properties ignore internal id property
		if (nodeDescription.hasIdProperty()) {
			Neo4jPersistentProperty idProperty = nodeDescription.getRequiredIdProperty();
			parameters.put(Constants.NAME_OF_ID,
					conversionService.writeValue(propertyAccessor.getProperty(idProperty), idProperty.getTypeInformation(), idProperty.getOptionalConverter()));
		}
		// in case of relationship properties ignore internal id property
		if (nodeDescription.hasVersionProperty()) {
			Long versionProperty = (Long) propertyAccessor.getProperty(nodeDescription.getRequiredVersionProperty());

			// we incremented this upfront the persist operation so the matching version would be one "before"
			parameters.put(Constants.NAME_OF_VERSION_PARAM, versionProperty);
		}
	}

	/**
	 * Merges the root node of a query and the remaining record into one map, adding the internal ID of the node, too.
	 * Merge happens only when the record contains additional values.
	 *
	 * @param node Node whose attributes are about to be merged
	 * @param record Record that should be merged
	 * @return A map accessor combining a {@link Node} and an arbitrary record
	 */
	private static MapAccessor mergeRootNodeWithRecord(Node node, MapAccessor record) {
		Map<String, Object> mergedAttributes = new HashMap<>(node.size() + record.size() + 1);

		mergedAttributes.put(Constants.NAME_OF_INTERNAL_ID, node.id());
		mergedAttributes.put(Constants.NAME_OF_LABELS, node.labels());
		mergedAttributes.putAll(node.asMap(Function.identity()));
		mergedAttributes.putAll(record.asMap(Function.identity()));

		return Values.value(mergedAttributes);
	}

	/**
	 * @param queryResult The original query result or a reduced form like a node or similar
	 * @param allValues The original query result
	 * @param nodeDescription The node description of the current entity to be mapped from the result
	 * @param <ET> As in entity type
	 * @return The mapped entity
	 */
	private <ET> ET map(MapAccessor queryResult, MapAccessor allValues, Neo4jPersistentEntity<ET> nodeDescription) {
		Collection<Relationship> relationshipsFromResult = extractRelationships(allValues);
		Collection<Node> nodesFromResult = extractNodes(allValues);
		return map(queryResult, nodeDescription, null, relationshipsFromResult, nodesFromResult);
	}

	private <ET> ET map(MapAccessor queryResult, Neo4jPersistentEntity<ET> nodeDescription,
			@Nullable Object lastMappedEntity, Collection<Relationship> relationshipsFromResult, Collection<Node> nodesFromResult) {

		// if the given result does not contain an identifier to the mapped object cannot get temporarily saved
		Long internalId = getInternalId(queryResult);

		Supplier<ET> mappedObjectSupplier = () -> {
			if (knownObjects.isInCreation(internalId)) {
				throw new MappingException(
						String.format(
								"The node with id %s has a logical cyclic mapping dependency; " +
								"its creation caused the creation of another node that has a reference to this",
								internalId)
				);
			}
			knownObjects.setInCreation(internalId);

			List<String> allLabels = getLabels(queryResult, nodeDescription);
			NodeDescriptionAndLabels nodeDescriptionAndLabels = nodeDescriptionStore
					.deriveConcreteNodeDescription(nodeDescription, allLabels);
			@SuppressWarnings("unchecked")
			Neo4jPersistentEntity<ET> concreteNodeDescription = (Neo4jPersistentEntity<ET>) nodeDescriptionAndLabels
					.getNodeDescription();

			ET instance = instantiate(concreteNodeDescription, queryResult,
					nodeDescriptionAndLabels.getDynamicLabels(), lastMappedEntity, relationshipsFromResult, nodesFromResult);

			knownObjects.removeFromInCreation(internalId);

			populateProperties(queryResult, nodeDescription, internalId, instance, lastMappedEntity, relationshipsFromResult, nodesFromResult, false);

			PersistentPropertyAccessor<ET> propertyAccessor = concreteNodeDescription.getPropertyAccessor(instance);
			ET bean = propertyAccessor.getBean();
			bean = eventSupport.maybeCallAfterConvert(bean, concreteNodeDescription, queryResult);

			// save final state of the bean
			knownObjects.storeObject(internalId, bean);
			return bean;
		};

		@SuppressWarnings("unchecked")
		ET mappedObject = (ET) knownObjects.getObject(internalId);
		if (mappedObject == null) {
			mappedObject = mappedObjectSupplier.get();
			knownObjects.storeObject(internalId, mappedObject);
		} else if (knownObjects.alreadyMappedInPreviousRecord(internalId)) {
			// If the object were created in a run before, it _could_ have missing relationships
			// (e.g. due to incomplete fetching by a custom query)
			// in such cases we will add the additional data from the next record.
			// This can and should only work for
			// 1. mutable owning types
			// AND (!!!)
			// 2. mutable target types
			// because we cannot just create new instances
			populateProperties(queryResult, nodeDescription, internalId, mappedObject, lastMappedEntity, relationshipsFromResult, nodesFromResult, true);
		}
		return mappedObject;
	}


	private <ET> void populateProperties(MapAccessor queryResult, Neo4jPersistentEntity<ET> nodeDescription, Long internalId,
										 ET mappedObject, @Nullable Object lastMappedEntity,
										 Collection<Relationship> relationshipsFromResult, Collection<Node> nodesFromResult, boolean objectAlreadyMapped) {

		List<String> allLabels = getLabels(queryResult, nodeDescription);
		NodeDescriptionAndLabels nodeDescriptionAndLabels = nodeDescriptionStore
				.deriveConcreteNodeDescription(nodeDescription, allLabels);

		@SuppressWarnings("unchecked")
		Neo4jPersistentEntity<ET> concreteNodeDescription = (Neo4jPersistentEntity<ET>) nodeDescriptionAndLabels
				.getNodeDescription();

		if (!concreteNodeDescription.requiresPropertyPopulation()) {
			return;
		}

		PersistentPropertyAccessor<ET> propertyAccessor = concreteNodeDescription.getPropertyAccessor(mappedObject);
		Predicate<Neo4jPersistentProperty> isConstructorParameter = concreteNodeDescription
				.getInstanceCreatorMetadata()::isCreatorParameter;

		// if the object were mapped before, we assume that at least all properties are populated
		if (!objectAlreadyMapped) {
			boolean isKotlinType = KotlinDetector.isKotlinType(concreteNodeDescription.getType());
			// Fill simple properties
			PropertyHandler<Neo4jPersistentProperty> handler = populateFrom(queryResult, propertyAccessor,
					isConstructorParameter, nodeDescriptionAndLabels.getDynamicLabels(), lastMappedEntity, isKotlinType);
			PropertyHandlerSupport.of(concreteNodeDescription).doWithProperties(handler);
		}
		// in a cyclic graph / with bidirectional relationships, we could end up in a state in which we
		// reference the start again. Because it is getting still constructed, it won't be in the knownObjects
		// store unless we temporarily put it there.
		knownObjects.storeObject(internalId, mappedObject);

		AssociationHandlerSupport.of(concreteNodeDescription).doWithAssociations(
				populateFrom(queryResult, nodeDescription, propertyAccessor, isConstructorParameter, objectAlreadyMapped, relationshipsFromResult, nodesFromResult));
	}

	@Nullable
	private Long getInternalId(@NonNull MapAccessor queryResult) {
		return queryResult instanceof Node
				? (Long) ((Node) queryResult).id()
				: queryResult.get(Constants.NAME_OF_INTERNAL_ID) == null || queryResult.get(Constants.NAME_OF_INTERNAL_ID).isNull()
				? null
				: queryResult.get(Constants.NAME_OF_INTERNAL_ID).asLong();
	}

	@NonNull
	private Neo4jPersistentEntity<?> getMostConcreteTargetNodeDescription(
			Neo4jPersistentEntity<?> genericTargetNodeDescription, MapAccessor possibleValueNode) {

		List<String> allLabels = getLabels(possibleValueNode, null);
		NodeDescriptionAndLabels nodeDescriptionAndLabels = nodeDescriptionStore
				.deriveConcreteNodeDescription(genericTargetNodeDescription, allLabels);
		return (Neo4jPersistentEntity<?>) nodeDescriptionAndLabels
				.getNodeDescription();
	}

	/**
	 * Returns the list of labels for the entity to be created from the "main" node returned.
	 *
	 * @param queryResult The complete query result
	 * @return The list of labels defined by the query variable {@link Constants#NAME_OF_LABELS}.
	 */
	@NonNull
	private List<String> getLabels(MapAccessor queryResult, @Nullable NodeDescription<?> nodeDescription) {
		Value labelsValue = queryResult.get(Constants.NAME_OF_LABELS);
		List<String> labels = new ArrayList<>();
		if (!labelsValue.isNull()) {
			labels = labelsValue.asList(Value::asString);
		} else if (queryResult instanceof Node) {
			Node nodeRepresentation = (Node) queryResult;
			nodeRepresentation.labels().forEach(labels::add);
		} else if (containsOnePlainNode(queryResult)) {
			for (Value value : queryResult.values()) {
				if (value.hasType(nodeType)) {
					Node node = value.asNode();
					for (String label : node.labels()) {
						labels.add(label);
					}
				}
			}
		} else if (!queryResult.get(Constants.NAME_OF_SYNTHESIZED_ROOT_NODE).isNull()) {
			queryResult.get(Constants.NAME_OF_SYNTHESIZED_ROOT_NODE).asNode().labels().forEach(labels::add);
		} else if (nodeDescription != null) {
			labels.addAll(nodeDescription.getStaticLabels());
		}
		return labels;
	}

	private boolean containsOnePlainNode(MapAccessor queryResult) {
		return StreamSupport.stream(queryResult.values().spliterator(), false)
				.filter(value -> value.hasType(nodeType)).count() == 1L;
	}

	private <ET> ET instantiate(Neo4jPersistentEntity<ET> nodeDescription, MapAccessor values,
			Collection<String> surplusLabels, @Nullable Object lastMappedEntity,
			Collection<Relationship> relationshipsFromResult, Collection<Node> nodesFromResult) {

		ParameterValueProvider<Neo4jPersistentProperty> parameterValueProvider = new ParameterValueProvider<Neo4jPersistentProperty>() {

			@SuppressWarnings("unchecked") // Needed for the last cast. It's easier that way than using the parameter type info and checking for primitives
			@Override
			public <T> T getParameterValue(Parameter<T, Neo4jPersistentProperty> parameter) {
				Neo4jPersistentProperty matchingProperty = nodeDescription.getRequiredPersistentProperty(parameter.getName());

				Object result;
				if (matchingProperty.isRelationship()) {
					RelationshipDescription relationshipDescription = nodeDescription.getRelationships().stream()
							.filter(r -> {
								String propertyFieldName = matchingProperty.getFieldName();
								return r.getFieldName().equals(propertyFieldName);
							}).findFirst().get();
					// If we cannot find any value it does not mean that there isn't any.
					// The result set might contain associations not named CONCRETE_TYPE_TARGET but ABSTRACT_TYPE_TARGET.
					// For this we bubble up the hierarchy of NodeDescriptions.
					result = createInstanceOfRelationships(matchingProperty, values, relationshipDescription, nodeDescription, relationshipsFromResult, nodesFromResult)
							.orElseGet(() -> {
								NodeDescription<?> parentNodeDescription = nodeDescription.getParentNodeDescription();
								T resultValue = null;
								while (parentNodeDescription != null) {
									Optional<Object> value = createInstanceOfRelationships(matchingProperty, values, relationshipDescription, parentNodeDescription, relationshipsFromResult, nodesFromResult);
									if (value.isPresent()) {
										resultValue = (T) value.get();
										break;
									}
									parentNodeDescription = parentNodeDescription.getParentNodeDescription();
								}
								return resultValue;
							});
				} else if (matchingProperty.isDynamicLabels()) {
					result = createDynamicLabelsProperty(matchingProperty.getTypeInformation(), surplusLabels);
				} else if (matchingProperty.isEntityWithRelationshipProperties()) {
					result = lastMappedEntity;
				} else {
					result =  conversionService.readValue(extractValueOf(matchingProperty, values), parameter.getType(), matchingProperty.getOptionalConverter());
				}
				return (T) result;
			}
		};

		return entityInstantiators.getInstantiatorFor(nodeDescription).createInstance(nodeDescription, parameterValueProvider);
	}

	private PropertyHandler<Neo4jPersistentProperty> populateFrom(MapAccessor queryResult,
			  PersistentPropertyAccessor<?> propertyAccessor, Predicate<Neo4jPersistentProperty> isConstructorParameter,
			  Collection<String> surplusLabels, @Nullable Object targetNode, boolean ownerIsKotlinType) {

		return property -> {
			if (isConstructorParameter.test(property)) {
				return;
			}

			TypeInformation<?> typeInformation = property.getTypeInformation();
			if (property.isDynamicLabels()) {
				propertyAccessor.setProperty(property,
						createDynamicLabelsProperty(typeInformation, surplusLabels));
			} else if (property.isAnnotationPresent(TargetNode.class)) {
				if (queryResult instanceof Relationship) {
					propertyAccessor.setProperty(property, targetNode);
				}
			} else {
				Object value = conversionService.readValue(extractValueOf(property, queryResult), typeInformation, property.getOptionalConverter());
				if (value != null) {
					Class<?> rawType = typeInformation.getType();
					propertyAccessor.setProperty(property, getValueOrDefault(ownerIsKotlinType, rawType, value));
				}
			}
		};
	}

	@Nullable
	private static Object getValueOrDefault(boolean ownerIsKotlinType, Class<?> rawType, @Nullable Object value) {

		return value == null && !ownerIsKotlinType && rawType.isPrimitive() ? ReflectionUtils.getPrimitiveDefault(rawType) : value;
	}

	private AssociationHandler<Neo4jPersistentProperty> populateFrom(MapAccessor queryResult, NodeDescription<?> baseDescription,
			PersistentPropertyAccessor<?> propertyAccessor, Predicate<Neo4jPersistentProperty> isConstructorParameter,
		    boolean objectAlreadyMapped, Collection<Relationship> relationshipsFromResult, Collection<Node> nodesFromResult) {

		return association -> {

			Neo4jPersistentProperty persistentProperty = association.getInverse();

			if (isConstructorParameter.test(persistentProperty)) {
				return;
			}

			if (objectAlreadyMapped) {

				// avoid multiple instances of the "same" object
				boolean willCreateNewInstance = persistentProperty.getWither() != null;
				if (willCreateNewInstance) {
					throw new MappingException("Cannot create a new instance of an already existing object");
				}
			}

			Object propertyValue = propertyAccessor.getProperty(persistentProperty);

			boolean propertyValueNotNull = propertyValue != null;

			boolean populatedCollection = persistentProperty.isCollectionLike()
					&& propertyValueNotNull
					&& !((Collection<?>) propertyValue).isEmpty();

			boolean populatedMap = persistentProperty.isMap()
					&& propertyValueNotNull
					&& !((Map<?, ?>) propertyValue).isEmpty();

			boolean populatedScalarValue = !persistentProperty.isCollectionLike() && !persistentProperty.isMap()
					&& propertyValueNotNull;

			boolean propertyAlreadyPopulated = populatedCollection || populatedMap || populatedScalarValue;

			// avoid unnecessary re-assignment of values
			if (propertyAlreadyPopulated) {
				return;
			}

			createInstanceOfRelationships(persistentProperty, queryResult, (RelationshipDescription) association, baseDescription, relationshipsFromResult, nodesFromResult)
					.ifPresent(value -> propertyAccessor.setProperty(persistentProperty, value));
		};
	}

	private Optional<Object> createInstanceOfRelationships(Neo4jPersistentProperty persistentProperty, MapAccessor values,
		   RelationshipDescription relationshipDescription, NodeDescription<?> baseDescription, Collection<Relationship> relationshipsFromResult,
		   Collection<Node> nodesFromResult) {

		String typeOfRelationship = relationshipDescription.getType();
		String targetLabel = relationshipDescription.getTarget().getPrimaryLabel();

		Neo4jPersistentEntity<?> genericTargetNodeDescription = (Neo4jPersistentEntity<?>) relationshipDescription
				.getTarget();

		List<Object> value = new ArrayList<>();
		Map<Object, Object> dynamicValue = new HashMap<>();

		BiConsumer<String, Object> mappedObjectHandler;
		Function<String, ?> keyTransformer;
		if (persistentProperty.isDynamicAssociation() && persistentProperty.getComponentType().isEnum()) {
			keyTransformer = f -> conversionService.convert(f, persistentProperty.getComponentType());
		} else {
			keyTransformer = Function.identity();
		}
		if (persistentProperty.isDynamicOneToManyAssociation()) {

			TypeInformation<?> actualType = persistentProperty.getTypeInformation().getRequiredActualType();
			mappedObjectHandler = (type, mappedObject) -> {
				@SuppressWarnings("unchecked")
				List<Object> bucket = (List<Object>) dynamicValue.computeIfAbsent(keyTransformer.apply(type),
						s -> CollectionFactory.createCollection(actualType.getType(), persistentProperty.getAssociationTargetType(), values.size()));
				bucket.add(mappedObject);
			};
		} else if (persistentProperty.isDynamicAssociation()) {
			mappedObjectHandler = (type, mappedObject) -> dynamicValue.put(keyTransformer.apply(type), mappedObject);
		} else {
			mappedObjectHandler = (type, mappedObject) -> value.add(mappedObject);
		}

		String collectionName = relationshipDescription.generateRelatedNodesCollectionName(baseDescription);

		Value list = values.get(collectionName);

		List<Object> relationshipsAndProperties = new ArrayList<>();

		if (Values.NULL.equals(list)) {
			Long sourceNodeId = getInternalId(values);

			Function<Relationship, Long> sourceIdSelector = relationshipDescription.isIncoming() ? Relationship::endNodeId : Relationship::startNodeId;
			Function<Relationship, Long> targetIdSelector = relationshipDescription.isIncoming() ? Relationship::startNodeId : Relationship::endNodeId;

			// Retrieve all matching relationships from the result's list(s)
			Collection<Relationship> allMatchingTypeRelationshipsInResult =
					extractMatchingRelationships(relationshipsFromResult, relationshipDescription, typeOfRelationship,
							(possibleRelationship) -> sourceIdSelector.apply(possibleRelationship).equals(sourceNodeId));

			// Retrieve all nodes from the result's list(s)
			Collection<Node> allNodesWithMatchingLabelInResult = extractMatchingNodes(nodesFromResult, targetLabel);

			for (Node possibleValueNode : allNodesWithMatchingLabelInResult) {
				long targetNodeId = possibleValueNode.id();

				Neo4jPersistentEntity<?> concreteTargetNodeDescription =
						getMostConcreteTargetNodeDescription(genericTargetNodeDescription, possibleValueNode);

				Set<Relationship> relationshipsProcessed = new HashSet<>();
				for (Relationship possibleRelationship : allMatchingTypeRelationshipsInResult) {
					if (targetIdSelector.apply(possibleRelationship) == targetNodeId) {

						// If the target is the same(equal) node, get the related object from the cache.
						// Avoiding the call to the map method also breaks an endless cycle of trying to finish
						// the property population of _this_ object.
						// The initial population will happen at the end of this mapping. This is sufficient because
						// it only affects properties not changing the instance of the object.
						Object mappedObject = sourceNodeId != null && sourceNodeId.equals(targetNodeId)
								? knownObjects.getObject(sourceNodeId)
								: map(possibleValueNode, concreteTargetNodeDescription, null, relationshipsFromResult, nodesFromResult);
						if (relationshipDescription.hasRelationshipProperties()) {

							Object relationshipProperties = map(possibleRelationship,
									(Neo4jPersistentEntity<?>) relationshipDescription.getRelationshipPropertiesEntity(),
									mappedObject, relationshipsFromResult, nodesFromResult);
							relationshipsAndProperties.add(relationshipProperties);
							mappedObjectHandler.accept(possibleRelationship.type(), relationshipProperties);
						} else {
							mappedObjectHandler.accept(possibleRelationship.type(), mappedObject);
						}
						relationshipsProcessed.add(possibleRelationship);
					}
				}
				allMatchingTypeRelationshipsInResult.removeAll(relationshipsProcessed);
			}
		} else {
			for (Value relatedEntity : list.asList(Function.identity())) {

				Neo4jPersistentEntity<?> concreteTargetNodeDescription =
						getMostConcreteTargetNodeDescription(genericTargetNodeDescription, relatedEntity);

				Object valueEntry = map(relatedEntity, concreteTargetNodeDescription, null, relationshipsFromResult, nodesFromResult);

				if (relationshipDescription.hasRelationshipProperties()) {
					String sourceLabel = relationshipDescription.getSource().getMostAbstractParentLabel(baseDescription);
					String relationshipSymbolicName = sourceLabel
													  + RelationshipDescription.NAME_OF_RELATIONSHIP + targetLabel;
					Relationship relatedEntityRelationship = relatedEntity.get(relationshipSymbolicName)
							.asRelationship();

					Object relationshipProperties = map(relatedEntityRelationship,
							(Neo4jPersistentEntity<?>) relationshipDescription.getRelationshipPropertiesEntity(),
							valueEntry, relationshipsFromResult, nodesFromResult);
					relationshipsAndProperties.add(relationshipProperties);
					mappedObjectHandler.accept(relatedEntity.get(RelationshipDescription.NAME_OF_RELATIONSHIP_TYPE).asString(), relationshipProperties);
				} else {
					mappedObjectHandler.accept(relatedEntity.get(RelationshipDescription.NAME_OF_RELATIONSHIP_TYPE).asString(),
							valueEntry);
				}
			}
		}

		if (persistentProperty.getTypeInformation().isCollectionLike()) {
			List<Object> returnedValues = relationshipDescription.hasRelationshipProperties() ?  relationshipsAndProperties : value;
			Collection<Object> target = CollectionFactory.createCollection(persistentProperty.getRawType(), persistentProperty.getComponentType(), returnedValues.size());
			target.addAll(returnedValues);
			return Optional.of(target);
		} else {
			if (relationshipDescription.isDynamic()) {
				return Optional.ofNullable(dynamicValue.isEmpty() ? null : dynamicValue);
			} else if (relationshipDescription.hasRelationshipProperties()) {
				return Optional.ofNullable(relationshipsAndProperties.isEmpty() ? null : relationshipsAndProperties.get(0));
			} else {
				return Optional.ofNullable(value.isEmpty() ? null : value.get(0));
			}
		}
	}

	private Collection<Node> extractMatchingNodes(Collection<Node> allNodesInResult, String targetLabel) {

		return labelNodeCache.computeIfAbsent(targetLabel, (label) -> {

			Predicate<Node> onlyWithMatchingLabels = n -> n.hasLabel(label);
			return allNodesInResult.stream()
					.filter(onlyWithMatchingLabels)
					.collect(Collectors.toList());
		});
	}

	private Collection<Node> extractNodes(MapAccessor allValues) {
		Collection<Node> allNodesInResult = new LinkedHashSet<>();
		StreamSupport.stream(allValues.values().spliterator(), false)
				.filter(MappingSupport.isListContainingOnly(listType, this.nodeType))
				.flatMap(entry -> MappingSupport.extractNodesFromCollection(listType, entry).stream())
				.forEach(allNodesInResult::add);

		StreamSupport.stream(allValues.values().spliterator(), false)
				.filter(this.nodeType::isTypeOf)
				.map(Value::asNode)
				.forEach(allNodesInResult::add);

		return allNodesInResult;
	}

	private Collection<Relationship> extractMatchingRelationships(Collection<Relationship> relationshipsFromResult,
									  RelationshipDescription relationshipDescription, String typeOfRelationship,
									  Predicate<Relationship> relationshipPredicate) {

		Predicate<Relationship> onlyWithMatchingType = r -> r.type().equals(typeOfRelationship) || relationshipDescription.isDynamic();
		return relationshipsFromResult.stream()
				.filter(onlyWithMatchingType.and(relationshipPredicate))
				.collect(Collectors.toList());
	}

	private Collection<Relationship> extractRelationships(MapAccessor allValues) {
		Collection<Relationship> allRelationshipsInResult = new LinkedHashSet<>();
		StreamSupport.stream(allValues.values().spliterator(), false)
				.filter(MappingSupport.isListContainingOnly(listType, this.relationshipType))
				.flatMap(entry -> MappingSupport.extractRelationshipsFromCollection(listType, entry).stream())
				.forEach(allRelationshipsInResult::add);

		StreamSupport.stream(allValues.values().spliterator(), false)
				.filter(this.relationshipType::isTypeOf)
				.map(Value::asRelationship)
				.forEach(allRelationshipsInResult::add);

		return allRelationshipsInResult;
	}

	private static Value extractValueOf(Neo4jPersistentProperty property, MapAccessor propertyContainer) {
		if (property.isInternalIdProperty()) {
			return propertyContainer instanceof Entity ? Values.value(((Entity) propertyContainer).id())
					: propertyContainer.get(Constants.NAME_OF_INTERNAL_ID);
		} else if (property.isComposite()) {
			String prefix = property.computePrefixWithDelimiter();

			if (propertyContainer.containsKey(Constants.NAME_OF_ALL_PROPERTIES)) {
				return extractCompositePropertyValues(propertyContainer.get(Constants.NAME_OF_ALL_PROPERTIES), prefix);
			} else {
				return extractCompositePropertyValues(propertyContainer, prefix);
			}
		} else {
			String graphPropertyName = property.getPropertyName();
			if (propertyContainer.containsKey(graphPropertyName)) {
				return propertyContainer.get(graphPropertyName);
			} else if (propertyContainer.containsKey(Constants.NAME_OF_ALL_PROPERTIES)) {
				return propertyContainer.get(Constants.NAME_OF_ALL_PROPERTIES).get(graphPropertyName);
			} else {
				return NullValue.NULL;
			}
		}
	}

	private static Value extractCompositePropertyValues(MapAccessor propertyContainer, String prefix) {
		Map<String, Value>  hlp = new HashMap<>(propertyContainer.size());
		propertyContainer.keys().forEach(k -> {
			if (k.startsWith(prefix)) {
				hlp.put(k, propertyContainer.get(k));
			}
		});
		return Values.value(hlp);
	}

	static class KnownObjects {

		private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
		private final Lock read = lock.readLock();
		private final Lock write = lock.writeLock();

		private final Map<Long, Object> internalIdStore = new HashMap<>();
		private final Map<Long, Boolean> internalCurrentRecord = new HashMap<>();
		private final Set<Long> previousRecords = new HashSet<>();
		private final Set<Long> idsInCreation = new HashSet<>();

		private void storeObject(@Nullable Long internalId, Object object) {
			if (internalId == null) {
				return;
			}
			try {
				write.lock();
				idsInCreation.remove(internalId);
				internalIdStore.put(internalId, object);
				internalCurrentRecord.put(internalId, false);
			} finally {
				write.unlock();
			}
		}

		private void setInCreation(@Nullable Long internalId) {
			if (internalId == null) {
				return;
			}
			try {
				write.lock();
				idsInCreation.add(internalId);
			} finally {
				write.unlock();
			}
		}

		private boolean isInCreation(@Nullable Long internalId) {
			if (internalId == null) {
				return false;
			}
			try {
				read.lock();
				return idsInCreation.contains(internalId);
			} finally {
				read.unlock();
			}
		}

		@Nullable
		private Object getObject(@Nullable Long internalId) {
			if (internalId == null) {
				return null;
			}
			try {

				read.lock();

				Object knownEntity = internalIdStore.get(internalId);

				if (knownEntity != null) {
					return knownEntity;
				}

			} finally {
				read.unlock();
			}
			return null;
		}

		private void removeFromInCreation(@Nullable Long internalId) {
			if (internalId == null) {
				return;
			}
			try {
				write.lock();
				idsInCreation.remove(internalId);
			} finally {
				write.unlock();
			}
		}

		private boolean alreadyMappedInPreviousRecord(@Nullable Long internalId) {
			if (internalId == null) {
				return false;
			}
			try {

				read.lock();

				return previousRecords.contains(internalId) || internalCurrentRecord.get(internalId);

			} finally {
				read.unlock();
			}
		}

		/**
		 * Mark all currently existing objects as mapped.
		 */
		private void nextRecord() {
			previousRecords.addAll(internalCurrentRecord.keySet());
			internalCurrentRecord.clear();
		}
	}
}
