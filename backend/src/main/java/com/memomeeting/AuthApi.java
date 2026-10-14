package com.memomeeting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import java.util.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthApi {
    public record Register(@NotBlank @Pattern(regexp="[A-Za-z0-9_]{3,40}") String username,@NotBlank @Size(max=60) String displayName,@NotNull @Size(min=8,max=72) String password) {}
    public record Login(@NotBlank @Size(max=40) String username,@NotNull @Size(min=1,max=72) String password) {}
    private final Accounts accounts;
    private final HttpSessionSecurityContextRepository repository;
    private final RateLimits limits;
    public AuthApi(Accounts accounts,HttpSessionSecurityContextRepository repository,RateLimits limits){this.accounts=accounts;this.repository=repository;this.limits=limits;}
    @GetMapping("/csrf") public Object csrf(CsrfToken token){return Map.of("token",token.getToken());}
    @GetMapping("/me") public Object me(){
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null && auth.getPrincipal() instanceof Identity id && !id.device())return Map.of("user",accounts.get(id.userId()));
        return Collections.singletonMap("user",null);
    }
    @PostMapping("/register") public Object register(@Valid @RequestBody Register input,HttpServletRequest request,HttpServletResponse response){
        limits.check("register:"+request.getRemoteAddr(),10);
        var user=accounts.register(input.username(),input.displayName(),input.password());login(user,request,response);return Map.of("user",user);
    }
    @PostMapping("/login") public Object login(@Valid @RequestBody Login input,HttpServletRequest request,HttpServletResponse response){
        limits.check("login:"+request.getRemoteAddr(),20);
        var user=accounts.login(input.username(),input.password());login(user,request,response);return Map.of("user",user);
    }
    private void login(Accounts.User user,HttpServletRequest request,HttpServletResponse response){
        request.getSession(true);request.changeSessionId();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(new Identity(user.id(),user.username(),user.displayName(),null),null,List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);repository.saveContext(context,request,response);
    }
    @PostMapping("/logout") public Object logout(HttpServletRequest request){Access.user();var session=request.getSession(false);if(session!=null)session.invalidate();SecurityContextHolder.clearContext();return Map.of("loggedOut",true);}
}
