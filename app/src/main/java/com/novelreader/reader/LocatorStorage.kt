package com.novelreader.reader

import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

object LocatorStorage {
    fun encode(locator: Locator): String = locator.toJSON().toString()
    fun decode(json: String): Locator? = runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()
}
