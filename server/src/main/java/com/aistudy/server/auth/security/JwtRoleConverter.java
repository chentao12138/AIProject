package com.aistudy.server.auth.security;

import java.util.Collection;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * BUSINESS-019 — deterministic JWT roles claim -> authority mapping.
 *
 * <p>Maps a JWT {@code roles} array such as {@code ["USER","ADMIN"]} to
 * Spring Security authorities {@code ROLE_USER} / {@code ROLE_ADMIN}.
 *
 * <p>This converter is wired explicitly into the production resource-server
 * chain and never touches the SPIKE decoder/converter.
 */
public class JwtRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object roles = jwt.getClaim("roles");
        if (roles instanceof Collection<?> raw) {
            return raw.stream()
                    .filter(item -> item instanceof String)
                    .map(item -> "ROLE_" + item.toString().trim().toUpperCase())
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());
        }
        return java.util.Collections.emptyList();
    }
}
