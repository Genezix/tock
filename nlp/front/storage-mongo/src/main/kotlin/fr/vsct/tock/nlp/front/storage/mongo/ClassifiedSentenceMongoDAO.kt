/*
 * Copyright (C) 2017 VSCT
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.vsct.tock.nlp.front.storage.mongo

import com.mongodb.ReadPreference.secondaryPreferred
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Collation
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.ReplaceOptions
import fr.vsct.tock.nlp.core.Intent
import fr.vsct.tock.nlp.front.service.storage.ClassifiedSentenceDAO
import fr.vsct.tock.nlp.front.shared.config.ApplicationDefinition
import fr.vsct.tock.nlp.front.shared.config.Classification
import fr.vsct.tock.nlp.front.shared.config.ClassifiedEntity_.Companion.Role
import fr.vsct.tock.nlp.front.shared.config.ClassifiedSentence
import fr.vsct.tock.nlp.front.shared.config.ClassifiedSentenceStatus
import fr.vsct.tock.nlp.front.shared.config.ClassifiedSentenceStatus.inbox
import fr.vsct.tock.nlp.front.shared.config.EntityDefinition
import fr.vsct.tock.nlp.front.shared.config.IntentDefinition
import fr.vsct.tock.nlp.front.shared.config.SentencesQuery
import fr.vsct.tock.nlp.front.shared.config.SentencesQueryResult
import fr.vsct.tock.nlp.front.storage.mongo.ClassifiedSentenceCol_.Companion.ApplicationId
import fr.vsct.tock.nlp.front.storage.mongo.ClassifiedSentenceCol_.Companion.ForReview
import fr.vsct.tock.nlp.front.storage.mongo.ClassifiedSentenceCol_.Companion.FullText
import fr.vsct.tock.nlp.front.storage.mongo.ClassifiedSentenceCol_.Companion.Language
import fr.vsct.tock.nlp.front.storage.mongo.ClassifiedSentenceCol_.Companion.LastEntityProbability
import fr.vsct.tock.nlp.front.storage.mongo.ClassifiedSentenceCol_.Companion.LastIntentProbability
import fr.vsct.tock.nlp.front.storage.mongo.ClassifiedSentenceCol_.Companion.LastUsage
import fr.vsct.tock.nlp.front.storage.mongo.ClassifiedSentenceCol_.Companion.Status
import fr.vsct.tock.nlp.front.storage.mongo.ClassifiedSentenceCol_.Companion.Text
import fr.vsct.tock.nlp.front.storage.mongo.ClassifiedSentenceCol_.Companion.UnknownCount
import fr.vsct.tock.nlp.front.storage.mongo.ClassifiedSentenceCol_.Companion.UpdateDate
import fr.vsct.tock.nlp.front.storage.mongo.ClassifiedSentenceCol_.Companion.UsageCount
import fr.vsct.tock.nlp.front.storage.mongo.MongoFrontConfiguration.database
import fr.vsct.tock.nlp.front.storage.mongo.ParseRequestLogMongoDAO.ParseRequestLogStatCol
import fr.vsct.tock.shared.defaultLocale
import fr.vsct.tock.shared.error
import fr.vsct.tock.shared.intProperty
import fr.vsct.tock.shared.security.UserLogin
import mu.KotlinLogging
import org.bson.conversions.Bson
import org.litote.jackson.data.JacksonData
import org.litote.kmongo.*
import org.litote.kmongo.MongoOperator.elemMatch
import org.litote.kmongo.MongoOperator.pull
import java.time.Instant
import java.time.Instant.now
import java.util.Locale
import java.util.concurrent.TimeUnit.DAYS
import kotlin.collections.toList


/**
 *
 */
internal object ClassifiedSentenceMongoDAO : ClassifiedSentenceDAO {

    private val logger = KotlinLogging.logger {}

    //alias
    private val Classification_ = ClassifiedSentenceCol_.Classification

    @Data(internal = true)
    @JacksonData(internal = true)
    data class ClassifiedSentenceCol(
        val text: String,
        val fullText: String = text,
        val language: Locale,
        val applicationId: Id<ApplicationDefinition>,
        val creationDate: Instant,
        val updateDate: Instant,
        val status: ClassifiedSentenceStatus,
        val classification: Classification,
        val lastIntentProbability: Double? = null,
        val lastEntityProbability: Double? = null,
        val lastUsage: Instant? = null,
        val usageCount: Long? = 0,
        val unknownCount: Long? = 0,
        val forReview: Boolean = false,
        val reviewComment: String? = null,
        val classifier: UserLogin? = null
    ) {

        constructor(sentence: ClassifiedSentence) :
                this(
                    textKey(sentence.text),
                    sentence.text,
                    sentence.language,
                    sentence.applicationId,
                    sentence.creationDate,
                    now(),
                    sentence.status,
                    sentence.classification,
                    sentence.lastIntentProbability,
                    sentence.lastEntityProbability,
                    sentence.lastUsage,
                    sentence.usageCount,
                    sentence.unknownCount,
                    sentence.forReview,
                    sentence.reviewComment,
                    sentence.qualifier
                )

        fun toSentence(): ClassifiedSentence =
            ClassifiedSentence(
                fullText,
                language,
                applicationId,
                creationDate,
                updateDate,
                status,
                classification,
                lastIntentProbability,
                lastEntityProbability,
                lastUsage,
                usageCount ?: 0,
                unknownCount ?: 0,
                forReview,
                reviewComment,
                classifier
            )
    }

    private val col: MongoCollection<ClassifiedSentenceCol> by lazy {
        val c = database.getCollection<ClassifiedSentenceCol>("classified_sentence")
        try {
            c.ensureUniqueIndex(Text, Language, ApplicationId)
            c.ensureIndex(Language, ApplicationId, Status)
            c.ensureIndex(Status)
            c.ensureIndex(UpdateDate)
            c.ensureIndex(orderBy(mapOf(ApplicationId to true, Language to true, UpdateDate to false)))
            c.ensureIndex(Language, ApplicationId, UsageCount)
            c.ensureIndex(Language, ApplicationId, UnknownCount)
            c.ensureIndex(Language, Status, Classification_.intentId)
            c.ensureIndex(
                ApplicationId, Classification_.intentId, Language, UpdateDate
            )
            c.ensureIndex(ForReview)
            intProperty("tock_nlp_classified_sentences_index_ttl_days", -1)
                .takeUnless { it == -1 }
                ?.let { ttl ->
                    c.ensureIndex(
                        UpdateDate,
                        indexOptions = IndexOptions()
                            .expireAfter(ttl.toLong(), DAYS)
                            .partialFilterExpression(Status eq inbox)
                    )
                }

        } catch (e: Exception) {
            logger.error(e)
        }
        c
    }

    override fun getSentences(
        intents: Set<Id<IntentDefinition>>?,
        language: Locale?,
        status: ClassifiedSentenceStatus?
    ): List<ClassifiedSentence> {
        if (intents == null && language == null && status == null) {
            error("at least one parameter should be not null")
        }

        return col
            .find(
                if (intents != null) Classification_.intentId `in` intents else null,
                if (language != null) Language eq language else null,
                if (status != null) Status eq status else null
            )
            .map { it.toSentence() }
            .toList()
    }

    override fun switchSentencesStatus(sentences: List<ClassifiedSentence>, newStatus: ClassifiedSentenceStatus) {
        sentences.forEach {
            save(it.copy(status = newStatus))
        }
    }

    override fun deleteSentencesByStatus(status: ClassifiedSentenceStatus) {
        col.deleteMany(Status eq status)
    }

    override fun deleteSentencesByApplicationId(applicationId: Id<ApplicationDefinition>) {
        col.deleteMany(ApplicationId eq applicationId)
    }

    override fun save(sentence: ClassifiedSentence) {
        col.replaceOneWithFilter(
            and(
                Text eq textKey(sentence.text),
                Language eq sentence.language,
                ApplicationId eq sentence.applicationId
            ),
            ClassifiedSentenceCol(sentence),
            ReplaceOptions().upsert(true)
        )
    }

    override fun search(query: SentencesQuery): SentencesQueryResult {
        with(query) {
            val filterBase =
                and(
                    ApplicationId eq applicationId,
                    filterLanguage(),
                    filterText(),
                    filterIntent(),
                    filterStatus(),
                    filterEntityType(),
                    filterEntityRolesToInclude(),
                    filterEntityRoleToExclude(),
                    filterModifiedAfter(),
                    filterModifiedBefore(),
                    filterReviewOnly()
                )

            logger.debug { filterBase.json }
            //read secondary for long term operations
            val c = if (onlyExactMatch) col else col.withReadPreference(secondaryPreferred())
            val count = c.countDocuments(filterBase)
            logger.debug { "count : $count" }
            if (count > start) {
                val list = c
                    .find(filterBase)
                    .run {
                        if (query.sort.isEmpty()) {
                            descendingSort(UpdateDate)
                        } else {
                            sort(
                                orderBy(
                                    query.sort.map {
                                        when (it.first) {
                                            "text" -> Text
                                            "currentIntent" -> Classification_.intentId
                                            "intentProbability" -> LastIntentProbability
                                            "entitiesProbability" -> LastEntityProbability
                                            "lastUpdate" -> UpdateDate
                                            "lastUsage" -> LastUsage
                                            "usageCount" -> UsageCount
                                            "unknownCount" -> UnknownCount
                                            else -> UpdateDate
                                        } to it.second
                                    }.toMap()
                                )
                            )
                        }
                    }
                    .run {
                        if (query.sort.isNotEmpty()) {
                            collation(
                                Collation
                                    .builder()
                                    .caseLevel(false)
                                    .locale((query.language ?: defaultLocale).toLanguageTag())
                                    .build()
                            )
                        } else {
                            this
                        }
                    }
                    .skip(start.toInt())
                    .limit(size)

                return SentencesQueryResult(count, list.map { it.toSentence() }.toList())
            } else {
                return SentencesQueryResult(count, emptyList())
            }
        }
    }

    private fun SentencesQuery.filterReviewOnly() = if (onlyToReview) ForReview eq true else null

    private fun SentencesQuery.filterModifiedAfter(): Bson? = when {
        modifiedAfter == null -> if (searchMark == null) null else UpdateDate lte searchMark!!.date
        searchMark == null -> UpdateDate gt modifiedAfter?.toInstant()
        else -> and(UpdateDate lte searchMark!!.date, UpdateDate gt modifiedAfter?.toInstant())
    }

    private fun SentencesQuery.filterModifiedBefore(): Bson? = when {
        modifiedBefore == null -> if (searchMark == null) null else UpdateDate gte searchMark!!.date
        searchMark == null -> UpdateDate lt modifiedBefore?.toInstant()
        else -> and(UpdateDate gte searchMark!!.date, UpdateDate lt modifiedBefore?.toInstant())
    }

    private fun SentencesQuery.filterEntityRoleToExclude(): Bson? = when {
        entityRolesToExclude.isEmpty() -> null
        searchSubEntities -> subEntityRoleQueryToExclude(entityRolesToExclude)
        else -> Classification_.entities.role `nin` entityRolesToExclude
    }

    private fun SentencesQuery.filterEntityRolesToInclude(): Bson? = when {
        entityRolesToInclude.isEmpty() -> null
        searchSubEntities -> subEntityRoleQueryToInclude(entityRolesToInclude)
        else -> Classification_.entities.role `in` entityRolesToInclude
    }

    private fun SentencesQuery.filterEntityType(): Bson? = when {
        entityType == null -> null
        searchSubEntities -> subEntityTypeQuery(entityType!!)
        else -> Classification_.entities.type eq entityType
    }

    private fun SentencesQuery.filterStatus() =
        if (status.isNotEmpty()) Status `in` status else if (notStatus != null) Status ne notStatus else null

    private fun SentencesQuery.filterIntent() = if (intentId == null) null else Classification_.intentId eq intentId

    private fun SentencesQuery.filterText(): Bson? = when {
        search.isNullOrBlank() -> null
        onlyExactMatch -> Text eq search
        else -> FullText.regex(search!!.trim(), "i")
    }

    private fun SentencesQuery.filterLanguage() = if (language == null) null else Language eq language

    //ugly
    private fun subEntityTypeQuery(entityType: String): Bson =
        or(
            Classification_.entities.type eq entityType,
            Classification_.entities.subEntities.type eq entityType,
            Classification_.entities.subEntities.subEntities.type eq entityType,
            Classification_.entities.subEntities.subEntities.subEntities.type eq entityType,
            Classification_.entities.subEntities.subEntities.subEntities.subEntities.type eq entityType,
            Classification_.entities.subEntities.subEntities.subEntities.subEntities.subEntities.type eq entityType,
            Classification_.entities.subEntities.subEntities.subEntities.subEntities.subEntities.subEntities.type eq entityType,
            Classification_.entities.subEntities.subEntities.subEntities.subEntities.subEntities.subEntities.subEntities.type eq entityType,
            Classification_.entities.subEntities.subEntities.subEntities.subEntities.subEntities.subEntities.subEntities.subEntities.type eq entityType
        )


    private fun subEntityRoleQueryToInclude(roles: List<String>): Bson =
        or(
            Classification_.entities.role `in` roles,
            Classification_.entities.subEntities.role `in` roles,
            Classification_.entities.subEntities.subEntities.role `in` roles,
            Classification_.entities.subEntities.subEntities.subEntities.role `in` roles,
            Classification_.entities.subEntities.subEntities.subEntities.subEntities.role `in` roles,
            Classification_.entities.subEntities.subEntities.subEntities.subEntities.subEntities.role `in` roles,
            Classification_.entities.subEntities.subEntities.subEntities.subEntities.subEntities.subEntities.role `in` roles,
            Classification_.entities.subEntities.subEntities.subEntities.subEntities.subEntities.subEntities.subEntities.role `in` roles,
            Classification_.entities.subEntities.subEntities.subEntities.subEntities.subEntities.subEntities.subEntities.subEntities.role `in` roles
        )

    private fun subEntityRoleQueryToExclude(roles: List<String>): Bson =
        and(
            Classification_.entities.role `nin` roles,
            Classification_.entities.subEntities.role `nin` roles,
            Classification_.entities.subEntities.subEntities.role `nin` roles,
            Classification_.entities.subEntities.subEntities.subEntities.role `nin` roles,
            Classification_.entities.subEntities.subEntities.subEntities.subEntities.role `nin` roles,
            Classification_.entities.subEntities.subEntities.subEntities.subEntities.subEntities.role `nin` roles,
            Classification_.entities.subEntities.subEntities.subEntities.subEntities.subEntities.subEntities.role `nin` roles,
            Classification_.entities.subEntities.subEntities.subEntities.subEntities.subEntities.subEntities.subEntities.role `nin` roles,
            Classification_.entities.subEntities.subEntities.subEntities.subEntities.subEntities.subEntities.subEntities.subEntities.role `nin` roles
        )

    override fun switchSentencesIntent(
        applicationId: Id<ApplicationDefinition>,
        oldIntentId: Id<IntentDefinition>,
        newIntentId: Id<IntentDefinition>
    ) {
        col.updateMany(
            and(
                ApplicationId eq applicationId,
                Classification_.intentId eq oldIntentId
            ),
            Classification_.intentId setTo newIntentId,
            Classification_.entities setTo emptyList(),
            Status setTo inbox
        )
    }

    override fun switchSentencesIntent(sentences: List<ClassifiedSentence>, newIntentId: Id<IntentDefinition>) {
        //TODO updateMany
        sentences.forEach {
            if (newIntentId.toString() == Intent.UNKNOWN_INTENT_NAME) {
                save(it.copy(classification = it.classification.copy(newIntentId, emptyList())))
            } else {
                save(it.copy(classification = it.classification.copy(newIntentId)))
            }
        }
    }

    override fun switchSentencesEntity(
        sentences: List<ClassifiedSentence>,
        oldEntity: EntityDefinition,
        newEntity: EntityDefinition
    ) {
        //TODO updateMany
        sentences.forEach {
            val selectedEntities =
                it.classification.entities.filter { it.role == oldEntity.role && it.type == oldEntity.entityTypeName }
            save(
                it.copy(
                    classification = it.classification.copy(
                        entities = it.classification.entities.filterNot { it.role == oldEntity.role && it.type == oldEntity.entityTypeName }
                                + selectedEntities.map {
                            it.copy(
                                type = newEntity.entityTypeName,
                                role = newEntity.role
                            )
                        }
                    )
                )
            )
        }
    }

    override fun removeEntityFromSentences(
        applicationId: Id<ApplicationDefinition>,
        intentId: Id<IntentDefinition>,
        entityType: String,
        role: String
    ) {
        col.updateMany(
            and(
                ApplicationId eq applicationId,
                Classification_.intentId eq intentId
            ),
            pullByFilter(Classification_.entities, Role eq role)
        )
    }

    override fun removeSubEntityFromSentences(
        applicationId: Id<ApplicationDefinition>,
        entityType: String,
        role: String
    ) {
        (1..10).forEach { removeSubEntitiesFromSentence(applicationId, entityType, role, it) }
    }

    private fun removeSubEntitiesFromSentence(
        applicationId: Id<ApplicationDefinition>,
        entityType: String,
        role: String,
        level: Int
    ) {
        val baseFilter = "classification.entities" + (2..level).joinToString("") { ".subEntities" }
        val filter = """{
            'applicationId':${applicationId.json},
            '$baseFilter':{
                $elemMatch :{
                    type:${entityType.json},
                    subEntities:{
                        $elemMatch:{
                            'role':${role.json}
                        }
                    }
                }
            }
        }"""
        val update = """{
                    $pull:{
                        '${baseFilter.replace(".subEntities", ".$[].subEntities")}.$.subEntities':{
                            'role':${role.json}
                            }
                        }
                    }"""
        logger.debug { "$filter $update" }
        col.updateMany(filter, update)
    }

    internal fun updateSentenceState(stat: ParseRequestLogStatCol) {
        col.updateOne(
            and(
                Language eq stat.language,
                ApplicationId eq stat.applicationId,
                Text eq stat.text
            ),
            combine(
                listOfNotNull(
                    stat.intentProbability?.let { set(LastIntentProbability, it) },
                    stat.entitiesProbability?.let { set(LastEntityProbability, it) },
                    set(LastUsage, stat.lastUsage),
                    set(UsageCount, stat.count)
                )
            )
        )
    }

    override fun incrementUnknownStat(
        applicationId: Id<ApplicationDefinition>,
        language: Locale,
        text: String
    ) {
        col.updateOne(
            and(
                Language eq language,
                ApplicationId eq applicationId,
                Text eq text
            ),
            inc(UnknownCount, 1)
        )
    }
}