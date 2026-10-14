package com.memomeeting;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

@Configuration
public class SecurityConfig {
    @Bean org.springframework.security.authentication.AuthenticationManager authenticationManager(Accounts accounts){
        return auth->{
            var user=accounts.login(auth.getName(),String.valueOf(auth.getCredentials()));
            return new UsernamePasswordAuthenticationToken(new Identity(user.id(),user.username(),user.displayName(),null),null,List.of(new SimpleGrantedAuthority("ROLE_USER")));
        };
    }
    @Bean HttpSessionSecurityContextRepository securityRepository(){return new HttpSessionSecurityContextRepository();}
    @Bean SecurityFilterChain security(HttpSecurity http,Devices devices,Settings settings,ObjectMapper json,HttpSessionSecurityContextRepository repository) throws Exception {
        var cors=new CorsConfiguration();cors.setAllowedOrigins(List.of(settings.allowedOrigins()));cors.setAllowCredentials(true);
        cors.setAllowedMethods(List.of("GET","POST","OPTIONS"));cors.setAllowedHeaders(List.of("Content-Type","Authorization","X-XSRF-TOKEN","X-Recorder-Token"));
        var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",cors);
        var csrf=new CookieCsrfTokenRepository();csrf.setCookieCustomizer(c->c.httpOnly(false).path("/").sameSite("Lax"));
        http.cors(c->c.configurationSource(source))
            .csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(request->request.getHeader("Authorization")!=null && request.getHeader("Authorization").startsWith("Bearer "),
                    request->request.getMethod().equals("POST") && Set.of("/api/v1/devices/pairings","/api/v1/devices/binding-claims","/api/v1/devices/binding-claims/voice").contains(request.getRequestURI())))
            .securityContext(c->c.securityContextRepository(repository))
            .requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
            .authorizeHttpRequests(c->c.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC,jakarta.servlet.DispatcherType.ERROR).permitAll().requestMatchers("/api/v1/auth/me","/api/v1/auth/csrf","/api/v1/auth/register","/api/v1/auth/login","/api/v1/devices/pairings","/api/v1/devices/binding-claims","/api/v1/devices/binding-claims/voice","/api/v1/devices/pairings/*","/api/v1/health","/api/health").permitAll()
                .requestMatchers("/api/v1/ai/**","/api/ai/**").denyAll().requestMatchers("/api/**","/ws/**").authenticated().anyRequest().permitAll())
            .exceptionHandling(c->c.authenticationEntryPoint((request,response,e)->{response.setStatus(401);response.setContentType("application/json;charset=UTF-8");json.writeValue(response.getWriter(),Map.of("message","请先登录或绑定设备"));})
                .accessDeniedHandler((request,response,e)->{response.setStatus(403);response.setContentType("application/json;charset=UTF-8");json.writeValue(response.getWriter(),Map.of("message","访问被拒绝，请刷新登录状态或检查 CSRF 请求头"));}))
            .addFilterBefore(new OncePerRequestFilter(){
                @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
                    String header=request.getHeader("Authorization");
                    if(header!=null && header.startsWith("Bearer ")){
                        SecurityContextHolder.clearContext();
                        var identity=devices.authenticate(header.substring(7));
                        if(identity!=null){var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(new UsernamePasswordAuthenticationToken(identity,null,List.of(new SimpleGrantedAuthority("ROLE_DEVICE"))));SecurityContextHolder.setContext(context);}
                    }
                    chain.doFilter(request,response);
                }
            },AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
