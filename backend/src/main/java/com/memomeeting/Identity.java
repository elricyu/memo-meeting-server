package com.memomeeting;

import java.io.Serializable;
/** Device identity carries its binding owner; clients cannot supply this owner. */
public record Identity(long userId,String username,String displayName,String deviceId) implements Serializable {
    public boolean device(){return deviceId!=null;}
}
