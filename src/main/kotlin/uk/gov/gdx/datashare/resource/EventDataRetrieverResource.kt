package uk.gov.gdx.datashare.resource

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uk.gov.gdx.datashare.service.EventDataRetrievalService

@RestController
@RequestMapping("/event-data-retrieval", produces = [ MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasAnyAuthority('SCOPE_data_retriever/read')")
class EventDataRetrieverResource(
  private val eventDataRetrievalService: EventDataRetrievalService
) {

  @GetMapping("/{id}")
  @Operation(
    summary = "Retrieves data from GDX and other services for return to client",
    description = "The event ID is the UUID received off the queue, Need scope of data_retriever/read",
    responses = [
      ApiResponse(
        responseCode = "200",
        description = "Data Returned"
      )
    ]
  )
  suspend fun getDataForEventId(
    @Schema(description = "Event ID", required = true, type = "UUID", pattern = "^[0-9a-fA-F]{8}\\b-[0-9a-fA-F]{4}\\b-[0-9a-fA-F]{4}\\b-[0-9a-fA-F]{4}\\b-[0-9a-fA-F]{12}\$")
    @PathVariable id: String,
  ): DataResponseMessage = eventDataRetrievalService.retrieveData(id)
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class DataResponseMessage(
  val eventType: String,
  val eventId: String,
  val details: Any? = null,
)
