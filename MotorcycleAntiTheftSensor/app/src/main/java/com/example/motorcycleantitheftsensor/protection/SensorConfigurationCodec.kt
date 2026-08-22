package com.example.motorcycleantitheftsensor.protection

import org.json.JSONArray
import org.json.JSONObject

class SensorConfigurationCodec(
    private val policy: SensorConfigurationPolicy = SensorConfigurationPolicy()
) {

    fun encode(config: SensorFusionConfiguration): String {
        val root = JSONObject()
        root.put("schemaVersion", config.schemaVersion)
        root.put("basePreset", config.basePreset.name)
        root.put("samplingProfile", config.samplingProfile.name)
        root.put("updatedAtMs", config.updatedAtMs)

        val capabilitiesArray = JSONArray()
        // Write capabilities in canonical enum order
        for (capability in SensorCapability.entries) {
            val capConfig = config.capability(capability)
            val capObj = JSONObject()
            capObj.put("capability", capConfig.capability.name)
            capObj.put("sensitivity", capConfig.sensitivity)
            capObj.put("correlationWindowMs", capConfig.correlationWindowMs)
            capObj.put("confirmationDurationMs", capConfig.confirmationDurationMs)

            val sourcesArray = JSONArray()
            val capSources = SensorSource.entries.filter { it.capability == capability }
            for (source in capSources) {
                val srcConfig = capConfig.source(source)
                val srcObj = JSONObject()
                srcObj.put("source", srcConfig.source.name)
                srcObj.put("role", srcConfig.role.name)
                if (srcConfig.thresholdOverride != null) {
                    srcObj.put("thresholdOverride", srcConfig.thresholdOverride)
                } else {
                    srcObj.put("thresholdOverride", JSONObject.NULL)
                }
                if (srcConfig.debounceOverrideMs != null) {
                    srcObj.put("debounceOverrideMs", srcConfig.debounceOverrideMs)
                } else {
                    srcObj.put("debounceOverrideMs", JSONObject.NULL)
                }
                if (srcConfig.samplingProfileOverride != null) {
                    srcObj.put("samplingProfileOverride", srcConfig.samplingProfileOverride.name)
                } else {
                    srcObj.put("samplingProfileOverride", JSONObject.NULL)
                }
                sourcesArray.put(srcObj)
            }
            capObj.put("sources", sourcesArray)
            capabilitiesArray.put(capObj)
        }
        root.put("capabilities", capabilitiesArray)
        return root.toString()
    }

    fun decode(jsonString: String): SensorFusionConfiguration {
        return try {
            val root = JSONObject(jsonString)
            val schemaVersion = root.optInt("schemaVersion", 1)
            if (schemaVersion > 1) {
                // Future schema fallback
                return policy.forPreset(SensorPreset.BALANCED)
            }

            val basePreset = try {
                SensorPreset.valueOf(root.getString("basePreset"))
            } catch (_: Exception) {
                SensorPreset.BALANCED
            }

            val samplingProfile = try {
                SensorSamplingProfile.valueOf(root.getString("samplingProfile"))
            } catch (_: Exception) {
                SensorSamplingProfile.BALANCED
            }

            val updatedAtMs = root.optLong("updatedAtMs", System.currentTimeMillis())

            val capabilitiesJson = root.optJSONArray("capabilities")
                ?: return policy.forPreset(basePreset, updatedAtMs)

            val capabilitiesMap = mutableMapOf<SensorCapability, SensorCapabilityConfiguration>()

            for (i in 0 until capabilitiesJson.length()) {
                val capObj = capabilitiesJson.getJSONObject(i)
                val capabilityName = capObj.getString("capability")
                val capability = try {
                    SensorCapability.valueOf(capabilityName)
                } catch (_: Exception) {
                    continue
                }

                val sensitivity = capObj.optInt("sensitivity", 5).coerceIn(1, 10)
                val correlationWindowMs = capObj.optLong("correlationWindowMs", SensorConfigurationPolicy.DEFAULT_CORRELATION_MS)
                    .coerceIn(SensorConfigurationPolicy.MIN_CORRELATION_MS, SensorConfigurationPolicy.MAX_CORRELATION_MS)
                val confirmationDurationMs = capObj.optLong("confirmationDurationMs", SensorConfigurationPolicy.DEFAULT_CONFIRMATION_MS)
                    .coerceIn(SensorConfigurationPolicy.MIN_CONFIRMATION_MS, SensorConfigurationPolicy.MAX_CONFIRMATION_MS)

                val sourcesJson = capObj.optJSONArray("sources")
                val sourcesMap = mutableMapOf<SensorSource, SensorSourceConfiguration>()

                if (sourcesJson != null) {
                    for (j in 0 until sourcesJson.length()) {
                        val srcObj = sourcesJson.getJSONObject(j)
                        val sourceName = srcObj.getString("source")
                        val source = try {
                            SensorSource.valueOf(sourceName)
                        } catch (_: Exception) {
                            continue
                        }
                        if (source.capability != capability) continue

                        val role = try {
                            SensorRole.valueOf(srcObj.getString("role"))
                        } catch (_: Exception) {
                            SensorRole.OFF
                        }

                        val thresholdOverride = if (srcObj.isNull("thresholdOverride")) null else srcObj.optDouble("thresholdOverride")
                        val debounceOverrideMs = if (srcObj.isNull("debounceOverrideMs")) null else srcObj.optLong("debounceOverrideMs")
                        val samplingProfileOverride = if (srcObj.isNull("samplingProfileOverride")) null else {
                            try {
                                SensorSamplingProfile.valueOf(srcObj.getString("samplingProfileOverride"))
                            } catch (_: Exception) {
                                null
                            }
                        }

                        sourcesMap[source] = SensorSourceConfiguration(
                            source = source,
                            role = role,
                            thresholdOverride = thresholdOverride?.takeIf { !it.isNaN() && !it.isInfinite() && it > 0.0 },
                            debounceOverrideMs = debounceOverrideMs?.coerceIn(SensorConfigurationPolicy.MIN_DEBOUNCE_MS, SensorConfigurationPolicy.MAX_DEBOUNCE_MS),
                            samplingProfileOverride = samplingProfileOverride,
                        )
                    }
                }

                // Fill missing sources for this capability
                for (source in SensorSource.entries.filter { it.capability == capability }) {
                    if (!sourcesMap.containsKey(source)) {
                        sourcesMap[source] = SensorSourceConfiguration(source, SensorRole.OFF)
                    }
                }

                capabilitiesMap[capability] = SensorCapabilityConfiguration(
                    capability = capability,
                    sensitivity = sensitivity,
                    correlationWindowMs = correlationWindowMs,
                    confirmationDurationMs = confirmationDurationMs,
                    sources = sourcesMap,
                )
            }

            // Fill missing capabilities if any
            for (capability in SensorCapability.entries) {
                if (!capabilitiesMap.containsKey(capability)) {
                    capabilitiesMap[capability] = policy.forPreset(basePreset, updatedAtMs).capability(capability)
                }
            }

            SensorFusionConfiguration(
                schemaVersion = 1,
                basePreset = basePreset,
                samplingProfile = samplingProfile,
                capabilities = capabilitiesMap,
                updatedAtMs = updatedAtMs,
            )
        } catch (_: Exception) {
            policy.forPreset(SensorPreset.BALANCED)
        }
    }
}
