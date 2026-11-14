package com.voicepulse.conversation.infrastructure.fallback;

import com.voicepulse.conversation.domain.model.CustomerProfile;
import com.voicepulse.conversation.domain.ports.outbound.CustomerLookupPort;
import com.voicepulse.shared.model.Pnr;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/**
 * Fallback no-op implementation of {@link CustomerLookupPort} (Null Object Pattern).
 *
 * <p>Registered as a Spring {@link Fallback} bean so that it is automatically superseded once the
 * concrete {@code mcp-crm-server} client adapter is registered.
 */
@Slf4j
@Fallback
@Component
public class NoOpCustomerLookupPort implements CustomerLookupPort {

    @Override
    public Optional<CustomerProfile> lookupCustomerByPnr(Pnr pnr) {
        log.debug("No-op CustomerLookupPort returning empty profile for pnr={}", pnr);
        return Optional.empty();
    }
}
