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
package org.springframework.data.neo4j.integration.shared.common;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Set;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

/**
 * @author Gerrit Meier
 */
@RequiredArgsConstructor(onConstructor = @__(@PersistenceCreator))
@Getter
@EqualsAndHashCode(of = { "id", "name" })
@Node
public class Pet {

	@Id @GeneratedValue private Long id;

	private final String name;

	public Pet(long id, String name) {
		this(name);
		this.id = id;
	}

	@Relationship("Has") private Set<Hobby> hobbies;

	@Relationship("Has") private List<Pet> friends;

	@Relationship(value = "Hated_by", direction = Relationship.Direction.INCOMING) private List<Pet> otherPets;

	@Relationship("Has") private List<ThingWithAssignedId> things;

	public Set<Hobby> getHobbies() {
		return hobbies;
	}

	public void setHobbies(Set<Hobby> hobbies) {
		this.hobbies = hobbies;
	}

	public List<Pet> getFriends() {
		return friends;
	}

	public void setFriends(List<Pet> friends) {
		this.friends = friends;
	}

	public Long getId() {
		return id;
	}
}
