package uk.gov.gdx.datashare.services

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient
import software.amazon.awssdk.services.cognitoidentityprovider.model.*
import uk.gov.gdx.datashare.enums.CognitoClientType
import uk.gov.gdx.datashare.models.CognitoClientRequest
import uk.gov.gdx.datashare.models.CognitoClientResponse

@Service
class CognitoService(
  @Value("\${cognito.user-pool-id:#{null}}") val userPoolId: String? = null,
  @Value("\${cognito.enabled:#{false}}") val enabled: Boolean = false,
  @Value("\${cognito.acquirer-scope:#{null}}") val acquirerScope: String? = null,
  @Value("\${cognito.supplier-scope:#{null}}") val supplierScope: String? = null,
  @Value("\${cognito.admin-scope:#{null}}") val adminScope: String? = null,
) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun createUserPoolClient(cognitoClientRequest: CognitoClientRequest): CognitoClientResponse {
    val scopes = cognitoClientRequest.clientTypes.map {
      when (it) {
        CognitoClientType.ACQUIRER -> acquirerScope
        CognitoClientType.SUPPLIER -> supplierScope
        CognitoClientType.ADMIN -> adminScope
      }
    }

    val userPoolClientRequest = baseUserPoolClientRequestBuilder
      .clientName(cognitoClientRequest.clientName)
      .userPoolId(userPoolId)

    scopes.forEach {
      userPoolClientRequest.allowedOAuthScopes(it)
    }

    val response = createCognitoClient()
      .createUserPoolClient(userPoolClientRequest.build())
      .userPoolClient()

    return CognitoClientResponse(
      clientName = response.clientName(),
      clientId = response.clientId(),
      clientSecret = response.clientSecret(),
    )
  }

  fun deleteUserPoolClient(clientId: String) {
    val deleteClientRequest = DeleteUserPoolClientRequest
      .builder()
      .clientId(clientId)
      .userPoolId(userPoolId)
      .build()
    try {
      createCognitoClient().deleteUserPoolClient(deleteClientRequest)
    } catch (e: ResourceNotFoundException) {
      log.warn("User pool client with ID: $clientId not found")
    }
  }

  private fun createCognitoClient() =
    if (enabled) {
      CognitoIdentityProviderClient.builder().build()
    } else {
      throw IllegalStateException("Cognito integration disabled")
    }

  // This must be kept in line with the terraform simple_user_pool_client/main.tf
  private val baseUserPoolClientRequestBuilder = CreateUserPoolClientRequest
    .builder()
    .allowedOAuthFlows(OAuthFlowType.CLIENT_CREDENTIALS)
    .allowedOAuthFlowsUserPoolClient(true)
    .accessTokenValidity(60)
    .idTokenValidity(60)
    .generateSecret(true)
    .explicitAuthFlows(ExplicitAuthFlowsType.ALLOW_USER_PASSWORD_AUTH, ExplicitAuthFlowsType.ALLOW_REFRESH_TOKEN_AUTH)
    .preventUserExistenceErrors("ENABLED")
    .enableTokenRevocation(false)
    .tokenValidityUnits(
      TokenValidityUnitsType
        .builder()
        .accessToken("minutes")
        .idToken("minutes")
        .refreshToken("days")
        .build(),
    )
}
