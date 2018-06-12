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

package fr.vsct.tock.bot.admin.model

import fr.vsct.tock.shared.defaultNamespace
import fr.vsct.tock.translator.I18nLabel
import fr.vsct.tock.translator.I18nLabelStat
import fr.vsct.tock.translator.I18nLocalizedLabel
import fr.vsct.tock.translator.UserInterfaceType.textChat
import org.litote.kmongo.Id
import java.time.Instant

/**
 * [I18nLabel] dto.
 */
data class BotI18nLabel(
    val _id: Id<I18nLabel>,
    val namespace: String = defaultNamespace,
    val category: String,
    val i18n: LinkedHashSet<BotI18nLocalizedLabel>,
    val defaultLabel: String? = null,
    val statCount: Int = 0,
    val lastUpdate: Instant? = null,
    val unhandledLocaleStats: List<I18nLabelStat> = emptyList()
) {

    companion object {
        private fun selectStats(
            label: I18nLocalizedLabel,
            labels: LinkedHashSet<I18nLocalizedLabel>,
            stats: List<I18nLabelStat>
        ): List<I18nLabelStat> =
            stats.filter { s ->
                label.locale == s.locale
                        && (
                        (label.interfaceType == s.interfaceType && s.connectorId == label.connectorId) ||
                                (label.interfaceType == s.interfaceType && labels.none { it.label.isNotBlank() && it.locale == s.locale && it.interfaceType == s.interfaceType && it.connectorId == s.connectorId }) ||
                                (label.interfaceType == textChat && labels.none { it.label.isNotBlank() && it.locale == s.locale && it.interfaceType == s.interfaceType })
                        )
            }
    }

    constructor(label: I18nLabel, stats: List<I18nLabelStat>) :
            this(
                label._id,
                label.namespace,
                label.category,
                label.i18n.mapTo(LinkedHashSet()) { BotI18nLocalizedLabel(it, selectStats(it, label.i18n, stats)) },
                label.defaultLabel,
                stats.sumBy { it.count },
                stats.maxBy { it.lastUpdate }?.lastUpdate,
                stats.filter { label.i18n.none { l -> l.locale == it.locale } }
            )

}