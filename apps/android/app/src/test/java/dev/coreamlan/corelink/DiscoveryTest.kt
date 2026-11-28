package dev.coreamlan.corelink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryTest {
    @Test fun derivesBroadcastOnlyFromPrivateSavedAddress() {
        assertEquals("192.168.43.255", CoreLinkClient.savedSubnetBroadcast("192.168.43.20"))
        assertEquals("10.1.2.255", CoreLinkClient.savedSubnetBroadcast("10.1.2.3"))
        assertNull(CoreLinkClient.savedSubnetBroadcast("8.8.8.8"))
        assertNull(CoreLinkClient.savedSubnetBroadcast("not-an-address"))
    }
    @Test fun acceptsOnlyExactPrivateEndpointAdvertisements() {
        assertTrue(CoreLinkClient.discoveryResponseIsValid("192.168.1.20", "CORELINK_HERE_V1:49876"))
        assertFalse(CoreLinkClient.discoveryResponseIsValid("8.8.8.8", "CORELINK_HERE_V1:49876"))
        assertFalse(CoreLinkClient.discoveryResponseIsValid("192.168.1.20", "CORELINK_HERE_V1:1234"))
        assertFalse(CoreLinkClient.discoveryResponseIsValid("192.168.1.20", "CORELINK_HERE_V1:49876\nextra"))
    }
}
