package uk.gov.gdx.datashare.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import uk.gov.gdx.datashare.e2e.http.Api
import java.util.*

@Tag("E2E")
class SupplierTest {
  private val underTest = Api()

  @Test
  fun `create and delete supplier with cognito client`() {
    val clientName = UUID.randomUUID().toString()
    val createResponse = underTest.createSupplierWithCognitoClient(clientName)

    assertThat(createResponse.clientName).isEqualTo(clientName)
    assertThat(createResponse.clientId).isNotNull()
    assertThat(createResponse.clientSecret).isNotNull()

    val suppliers = underTest.getSuppliers()
    assertThat(suppliers.filter { it.name == clientName }).hasSize(1)

    val supplier = suppliers.find { it.name == clientName }!!

    val newSupplierSubscriptions = underTest.getSupplierSubscriptionsForSupplier(supplier.id)
    assertThat(newSupplierSubscriptions).hasSize(1)
    assertThat(newSupplierSubscriptions).first().hasFieldOrPropertyWithValue("clientId", createResponse.clientId)

    underTest.deleteSupplier(supplier.id)

    val remainingSupplierSubscriptions = underTest.getSupplierSubscriptions()
    val remainingSuppliers = underTest.getSuppliers()
    assertThat(remainingSuppliers.filter { it.name == clientName }).isEmpty()
    assertThat(remainingSupplierSubscriptions.filter { it.supplierId == supplier.id }).isEmpty()
  }
}
