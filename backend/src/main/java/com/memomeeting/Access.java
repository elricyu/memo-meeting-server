package com.memomeeting;

import org.springframework.stereotype.Component;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.security.Principal;

@Component
public class Access {
    private final Store store;
    private final Accounts accounts;
    public Access(Store store,Accounts accounts){this.store=store;this.accounts=accounts;}
    public Identity admin(){var id=user();if(!accounts.admin(id.userId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"此操作仅限管理员");return id;}
    public static Identity from(Principal principal){
        if(principal instanceof Authentication auth && auth.getPrincipal() instanceof Identity id)return id;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录或绑定设备");
    }
    public static Identity current(){return from(SecurityContextHolder.getContext().getAuthentication());}
    public static Identity user(){var identity=current();if(identity.device())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"此操作需要用户登录");return identity;}
    public void meeting(String id){meeting(id,current());}
    public void meeting(String id,Identity identity){
        if(identity.device() && !store.deviceActive(identity.deviceId(),identity.userId()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"设备已解绑");
        var m=store.get(id);
        if(m.ownerId()==null || m.ownerId()!=identity.userId() || (identity.device() && !identity.deviceId().equals(m.deviceId())))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"记录不存在");
    }
    public static String actor(Identity identity){return identity.device()?"device:"+identity.deviceId():"user:"+identity.userId();}
}
