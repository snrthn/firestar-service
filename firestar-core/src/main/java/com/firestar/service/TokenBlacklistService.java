package com.firestar.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.firestar.entity.TokenBlacklist;
import com.firestar.mapper.TokenBlacklistMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Token 黑名单服务：logout 时把 JWT 的 jti 拉黑，filter 在请求时查表判定是否失效。
 * 过期行可由定时任务清理（不在本期范围，expire_at 已预留索引）。
 */
@Service
public class TokenBlacklistService {

    private final TokenBlacklistMapper mapper;

    public TokenBlacklistService(TokenBlacklistMapper mapper) {
        this.mapper = mapper;
    }

    public void blacklist(String tokenId, String username, LocalDateTime expireAt) {
        TokenBlacklist entity = new TokenBlacklist();
        entity.setTokenId(tokenId);
        entity.setUsername(username);
        entity.setExpireAt(expireAt);
        mapper.insert(entity);
    }

    public boolean isBlacklisted(String tokenId) {
        if (tokenId == null) {
            return false;
        }
        Long cnt = mapper.selectCount(
                new LambdaQueryWrapper<TokenBlacklist>().eq(TokenBlacklist::getTokenId, tokenId));
        return cnt != null && cnt > 0;
    }
}
