package com.example.motorcycleantitheftsensor.sensor

import com.example.motorcycleantitheftsensor.protection.SensorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class AndroidSensorCatalogTest {

    @Test
    fun nullSensorManagerReturnsUnavailableDescriptorsForEverySource() {
        val catalog = AndroidSensorCatalog(null)
        val descriptors = catalog.descriptors()

        assertEquals(SensorSource.entries.size, descriptors.size)
        SensorSource.entries.forEach { source ->
            val descriptor = catalog.descriptor(source)
            assertNotNull(descriptor)
            assertFalse(descriptor.isAvailable)
            assertEquals(SensorTypeMap.androidType(source), descriptor.androidType)
            assertFalse(catalog.isAvailable(source))
        }
    }
}
