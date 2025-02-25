package uk.gov.gdx.datashare.services

import com.amazonaws.xray.spring.aop.XRayEnabled
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.gdx.datashare.enums.EnrichmentField
import uk.gov.gdx.datashare.enums.EventType
import uk.gov.gdx.datashare.models.DeathNotificationDetails

@Service
@XRayEnabled
class DeathNotificationService(
  private val levApiService: LevApiService,
) : EnrichmentService {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  override fun accepts(eventType: EventType): Boolean {
    return eventType == EventType.DEATH_NOTIFICATION
  }

  override fun process(dataId: String, enrichmentFields: List<EnrichmentField>): DeathNotificationDetails? {
    val citizenDeathId = dataId.toInt()
    return levApiService
      .findDeathById(citizenDeathId)
      .first()
      .let { DeathNotificationDetails.from(enrichmentFields, it) }
  }
}
