package com.celoext

import com.celoext.api.parseModelInfo
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OpenCode 2.2.5 returns model prices as an array of tiers with the cache prices nested.
 * The cost hint under the composer reads a flat input/output/cache_read object, so the
 * parser has to bring both shapes to that form. Before this, every paid model looked free.
 */
class ModelCostTest {
    private fun price(m: kotlinx.serialization.json.JsonObject?, key: String) =
        m?.get(key)?.jsonPrimitive?.doubleOrNull

    @Test fun readsTheBaseTierOfTheV2CostArray() {
        val model = parseModelInfo(buildJsonObject {
            put("id", "paid-model")
            put("providerID", "anthropic")
            putJsonArray("cost") {
                add(buildJsonObject {
                    put("input", 3.0)
                    put("output", 15.0)
                    putJsonObject("cache") { put("read", 0.3); put("write", 3.75) }
                })
                add(buildJsonObject { put("input", 6.0); put("output", 30.0) })
            }
        })

        assertEquals(3.0, price(model.cost, "input")!!, 1e-9)
        assertEquals(15.0, price(model.cost, "output")!!, 1e-9)
        assertEquals(0.3, price(model.cost, "cache_read")!!, 1e-9)
        assertEquals(3.75, price(model.cost, "cache_write")!!, 1e-9)
    }

    @Test fun keepsTheOldFlatCostObject() {
        val model = parseModelInfo(buildJsonObject {
            put("id", "old-model")
            put("providerID", "openai")
            putJsonObject("cost") {
                put("input", 1.0)
                put("output", 2.0)
                put("cache_read", 0.1)
            }
        })

        assertEquals(1.0, price(model.cost, "input")!!, 1e-9)
        assertEquals(2.0, price(model.cost, "output")!!, 1e-9)
        assertEquals(0.1, price(model.cost, "cache_read")!!, 1e-9)
    }

    @Test fun noPriceStaysNull() {
        val model = parseModelInfo(buildJsonObject {
            put("id", "free-model")
            put("providerID", "opencode")
        })
        assertNull(model.cost)
    }

    @Test fun emptyArrayStaysNull() {
        val model = parseModelInfo(buildJsonObject {
            put("id", "free-model")
            put("providerID", "opencode")
            putJsonArray("cost") { }
        })
        assertNull(model.cost)
    }
}
