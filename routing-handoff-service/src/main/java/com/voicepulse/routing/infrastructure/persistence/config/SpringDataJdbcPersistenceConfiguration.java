package com.voicepulse.routing.infrastructure.persistence.config;

import com.voicepulse.routing.infrastructure.persistence.JsonbColumnCodec;
import com.voicepulse.routing.infrastructure.persistence.converter.JsonbDocumentToJdbcValueConverter;
import com.voicepulse.routing.infrastructure.persistence.converter.PgObjectToJsonbDocumentConverter;
import com.voicepulse.routing.infrastructure.persistence.converter.StringToJsonbDocumentConverter;
import com.voicepulse.routing.infrastructure.persistence.model.CallSessionEntity;
import com.voicepulse.routing.infrastructure.persistence.model.DisruptionTicketEntity;
import com.voicepulse.routing.infrastructure.persistence.model.OperatorHandoffLogEntity;
import com.voicepulse.routing.infrastructure.persistence.repository.CallSessionRepository;
import java.util.List;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

/**
 * Spring Data JDBC configuration registering PostgreSQL {@code JSONB} custom converters and
 * declarative repositories when persistence is enabled.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "voicepulse.routing.persistence",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableJdbcRepositories(basePackageClasses = CallSessionRepository.class)
@RequiredArgsConstructor
public class SpringDataJdbcPersistenceConfiguration extends AbstractJdbcConfiguration {

    private final @NonNull JsonbColumnCodec jsonbCodec;

    @Override
    protected Set<Class<?>> getInitialEntitySet() {
        return Set.of(
                CallSessionEntity.class,
                DisruptionTicketEntity.class,
                OperatorHandoffLogEntity.class);
    }

    @Override
    protected List<?> userConverters() {
        return List.of(
                new JsonbDocumentToJdbcValueConverter(jsonbCodec),
                new PgObjectToJsonbDocumentConverter(jsonbCodec),
                new StringToJsonbDocumentConverter(jsonbCodec));
    }
}
