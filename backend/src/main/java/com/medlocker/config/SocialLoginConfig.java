package com.medlocker.config;

import com.medlocker.controller.SocialAuthController;
import com.medlocker.dto.Api;
import com.medlocker.service.SocialAccounts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration
public class SocialLoginConfig {
    private static final String BASE = "/oauth2/authorization";
    private static final String REDIRECT = "{baseUrl}/login/oauth2/code/{registrationId}";

    @Bean
    ClientRegistrationRepository socialProviders(Environment env) {
        var providers = new HashMap<String, ClientRegistration>();
        String googleId = env.getProperty("MEDLOCKER_GOOGLE_CLIENT_ID", "");
        String googleSecret = env.getProperty("MEDLOCKER_GOOGLE_CLIENT_SECRET", "");
        if (!googleId.isBlank() && !googleSecret.isBlank()) {
            providers.put("google", CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId(googleId).clientSecret(googleSecret).scope("openid")
                    .redirectUri(REDIRECT)
                    .issuerUri("https://accounts.google.com").build());
        }
        String appleId = env.getProperty("MEDLOCKER_APPLE_CLIENT_ID", "");
        String appleSecret = env.getProperty("MEDLOCKER_APPLE_CLIENT_SECRET", "");
        if (!appleId.isBlank() && !appleSecret.isBlank()) {
            String issuer = "https://appleid.apple.com";
            providers.put("apple", oidc("apple", appleId, appleSecret)
                    .authorizationUri(issuer + "/auth/authorize").tokenUri(issuer + "/auth/token")
                    .issuerUri(issuer).jwkSetUri(issuer + "/auth/keys").build());
        }
        String tenant = env.getProperty("MEDLOCKER_MICROSOFT_TENANT_ID", "");
        String microsoftId = env.getProperty("MEDLOCKER_MICROSOFT_CLIENT_ID", "");
        String microsoftSecret = env.getProperty("MEDLOCKER_MICROSOFT_CLIENT_SECRET", "");
        if (!tenant.isBlank() && !microsoftId.isBlank() && !microsoftSecret.isBlank()) {
            String authority = "https://login.microsoftonline.com/" + UUID.fromString(tenant);
            providers.put("microsoft", oidc("microsoft", microsoftId, microsoftSecret)
                    .authorizationUri(authority + "/oauth2/v2.0/authorize")
                    .tokenUri(authority + "/oauth2/v2.0/token").issuerUri(authority + "/v2.0")
                    .jwkSetUri(authority + "/discovery/v2.0/keys").build());
        }
        return providers::get;
    }

    private static ClientRegistration.Builder oidc(String provider, String id, String secret) {
        return ClientRegistration.withRegistrationId(provider).clientId(id).clientSecret(secret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT).scope("openid").userNameAttributeName("sub");
    }

    static void configure(HttpSecurity http, ClientRegistrationRepository providers, SocialAccounts accounts)
            throws Exception {
        http.oauth2Login(login -> login.clientRegistrationRepository(providers)
                .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver(providers)))
                .authorizedClientRepository(new DiscardAuthorizedClients())
                .successHandler((request, response, authentication) -> {
                    var flow = flow(request);
                    if (flow == null || !(authentication instanceof OAuth2AuthenticationToken oauth)
                            || !flow.provider().equals(oauth.getAuthorizedClientRegistrationId())
                            || !(oauth.getPrincipal() instanceof OidcUser identity)) {
                        finish(request, response, flow, new Api.SocialResult(null, "Could not verify this sign-in."));
                        return;
                    }
                    finish(request, response, flow, accounts.complete(flow, identity.getSubject()));
                })
                .failureHandler((request, response, error) -> finish(request, response, flow(request),
                        new Api.SocialResult(null, "Provider sign-in did not complete. Try again."))));
    }

    private static SocialAccounts.Flow flow(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null && session.getAttribute(SocialAuthController.FLOW) instanceof SocialAccounts.Flow flow) {
            return flow;
        }
        return null;
    }

    private static void finish(HttpServletRequest request, HttpServletResponse response,
                               SocialAccounts.Flow flow, Api.SocialResult result) throws IOException {
        if (flow == null) {
            response.sendRedirect("/");
            return;
        }
        var session = request.getSession(false);
        session.removeAttribute(SocialAuthController.FLOW);
        session.setAttribute(SocialAuthController.RESULT, result);
        response.sendRedirect(flow.path() + "?social=return");
    }

    private static OAuth2AuthorizationRequestResolver resolver(ClientRegistrationRepository providers) {
        var delegate = new DefaultOAuth2AuthorizationRequestResolver(providers, BASE);
        return new OAuth2AuthorizationRequestResolver() {
            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
                var flow = flow(request);
                if (flow == null || !request.getServletPath().equals(BASE + "/" + flow.provider())) return null;
                return resolve(request, flow.provider());
            }

            @Override
            public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String id) {
                var flow = flow(request);
                if (flow == null || !flow.provider().equals(id)) return null;
                var authorization = delegate.resolve(request, id);
                if (!id.equals("apple")) return authorization;
                // Apple accepts a query callback for a code-only request without profile scopes.
                String uri = UriComponentsBuilder.fromUriString(authorization.getAuthorizationRequestUri())
                        .replaceQueryParam("scope").build(true).toUriString();
                return OAuth2AuthorizationRequest.from(authorization).authorizationRequestUri(uri).build();
            }
        };
    }

    private static final class DiscardAuthorizedClients implements OAuth2AuthorizedClientRepository {
        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String id, Authentication principal,
                                                                         HttpServletRequest request) { return null; }
        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
                                         HttpServletRequest request, HttpServletResponse response) {}
        @Override
        public void removeAuthorizedClient(String id, Authentication principal,
                                           HttpServletRequest request, HttpServletResponse response) {}
    }
}
