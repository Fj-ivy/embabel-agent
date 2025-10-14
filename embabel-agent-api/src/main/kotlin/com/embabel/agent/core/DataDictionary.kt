/*
 * Copyright 2024-2025 Embabel Software, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.core

/**
 * Represents a relationship between two domain types.
 * @param from The source domain type
 * @param to The target domain type
 * @param name The name of the relationship (inferred from property name)
 * @param cardinality The cardinality of the relationship
 */
data class AllowedRelationship(
    val from: DomainType,
    val to: DomainType,
    val name: String,
    val description: String = name,
    val cardinality: Cardinality,
)

/**
 * Exposes access to a set of known data types
 */
interface DataDictionary {

    /**
     * All known types referenced by this component.
     * These may or may not be backed by JVM objects.
     */
    val domainTypes: Collection<DomainType>

    val dynamicTypes: Collection<DynamicType>
        get() =
            domainTypes.filterIsInstance<DynamicType>().toSet()

    val jvmTypes: Collection<JvmType>
        get() =
            domainTypes.filterIsInstance<JvmType>().toSet()

    /**
     * Get all relationships between domain types in this dictionary.
     * A relationship is a property that references another DomainType (not a simple property).
     * @return List of all possible relationships
     */
    fun allowedRelationships(): List<AllowedRelationship> {
        val relationships = mutableListOf<AllowedRelationship>()
        domainTypes.forEach { domainType ->
            domainType.properties.forEach { property ->
                if (property is DomainTypePropertyDefinition) {
                    relationships.add(
                        AllowedRelationship(
                            from = domainType,
                            to = property.type,
                            name = property.name,
                            cardinality = property.cardinality,
                        )
                    )
                }
            }
        }
        return relationships
    }

    /**
     * The domain type matching these labels, if we have one
     */
    fun domainTypeForLabels(labels: Set<String>): DomainType? {
        return domainTypes.maxByOrNull { entity ->
            entity.labels.intersect(labels).size
        }
    }

}

class DataDictionaryImpl(
    override val domainTypes: Collection<DomainType>,
) : DataDictionary {

    constructor (
        vararg embabelTypes: Class<*>,
    ) : this(embabelTypes.map { JvmType(it) })
}
