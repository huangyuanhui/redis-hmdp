package com.hmdp.dto;

import lombok.Data;

@Data
public class UserDTO {
    // 用户ID
    private Long id;
    // 用户昵称
    private String nickName;
    // 用户头像
    private String icon;
}
