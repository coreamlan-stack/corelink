package dev.coreamlan.corelink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {
    @Test fun acceptsOnlyExactPrivateEndpointAdvertisements() {
        assertTrue(CoreLinkClient.discoveryResponseIsValid("192.168.1.20", "CORELINK_HERE_V1:49876"))
        assertFalse(CoreLinkClient.discoveryResponseIsValid("8.8.8.8", "CORELINK_HERE_V1:49876"))
        assertFalse(CoreLinkClient.discoveryResponseIsValid("192.168.1.20", "CORELINK_HERE_V1:1234"))
        assertFalse(CoreLinkClient.discoveryResponseIsValid("192.168.1.20", "CORELINK_HERE_V1:49876\nextra"))
    }
}
