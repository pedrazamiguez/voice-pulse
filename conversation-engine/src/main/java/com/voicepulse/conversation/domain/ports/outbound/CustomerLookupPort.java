package com.voicepulse.conversation.domain.ports.outbound;

import com.voicepulse.conversation.domain.model.CustomerProfile;
import com.voicepulse.shared.model.Pnr;
import java.util.Optional;

/**
 * Driven (outbound) port for querying passenger identity, loyalty tier, and active disrupted
 * itinerary via {@code mcp-crm-server} (RFC-0001 §4.4 & RFC-0003 §4.1).
 */
public interface CustomerLookupPort {

    /**
     * Looks up a passenger profile by their 6-character PNR booking reference.
     *
     * @param pnr acoustically verified 6-character PNR
     * @return passenger profile and active itinerary if found, or {@link Optional#empty()}
     */
    Optional<CustomerProfile> lookupCustomerByPnr(Pnr pnr);
}
