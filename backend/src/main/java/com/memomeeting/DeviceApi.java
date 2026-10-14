package com.memomeeting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceApi {
    public record Pair(@NotBlank @Size(max=128) String clientId,@NotNull @Pattern(regexp="(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}") String mac,@NotBlank @Size(max=64) String displayName,
                       @NotBlank @Size(max=32) String platform,@NotNull @Pattern(regexp="[a-f0-9]{64}") String deviceToken,
                       @NotNull @Pattern(regexp="[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}") String clientRequestId) {}
    public record Bind(@NotBlank @Size(max=16) String code) {}
    private final Devices devices;
    private final RateLimits limits;
    public DeviceApi(Devices devices,RateLimits limits){this.devices=devices;this.limits=limits;}
    @PostMapping("/pairings") public Object pair(@Valid @RequestBody Pair input,HttpServletRequest request){limits.check("pair:"+request.getRemoteAddr(),20);return devices.initiate(input.clientId().trim(),input.mac().toUpperCase(Locale.ROOT),input.displayName().trim(),input.platform().trim(),input.deviceToken(),input.clientRequestId());}
    @GetMapping("/pairings/{id}") public Object poll(@PathVariable String id,@RequestHeader("Authorization") String header){return devices.poll(id,header.startsWith("Bearer ")?header.substring(7):null);}
    @GetMapping("/pairings/preview") public Object preview(@RequestParam @Size(max=16) String code){var user=Access.user();limits.check("preview:"+user.userId(),20);return devices.preview(code);}
    @PostMapping("/bind") public Object bind(@Valid @RequestBody Bind input){var user=Access.user();limits.check("bind:"+user.userId(),20);return devices.bind(input.code(),user.userId());}
    @GetMapping public Object list(){return devices.list(Access.user().userId());}
    @PostMapping("/{id}/unbind") public Object unbind(@PathVariable String id){devices.unbind(id,Access.user().userId());return Map.of("unbound",true);}
}
