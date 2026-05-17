package com.tokenrelay.gateway.service;

import com.tokenrelay.gateway.config.GatewayProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AdminIpAllowlistService {
  private final boolean allowAll;
  private final Set<String> exactIps;
  private final List<CidrBlock> cidrBlocks;

  public AdminIpAllowlistService(GatewayProperties properties) {
    boolean localAllowAll = false;
    Set<String> localExactIps = new HashSet<>();
    List<CidrBlock> localCidrBlocks = new ArrayList<>();

    List<String> entries = properties.adminIpWhitelist();
    if (entries != null) {
      for (String entry : entries) {
        if (entry == null || entry.isBlank()) {
          continue;
        }
        String normalized = entry.trim();
        if ("*".equals(normalized)) {
          localAllowAll = true;
          continue;
        }
        if (normalized.contains("/")) {
          localCidrBlocks.add(CidrBlock.parse(normalized));
        } else {
          localExactIps.add(normalizeIp(normalized));
        }
      }
    }

    this.allowAll = localAllowAll;
    this.exactIps = Set.copyOf(localExactIps);
    this.cidrBlocks = List.copyOf(localCidrBlocks);
  }

  public boolean isAllowed(String ip) {
    if (allowAll) {
      return true;
    }
    if (ip == null || ip.isBlank()) {
      return false;
    }

    String normalizedIp = normalizeIp(ip.trim());
    if (exactIps.contains(normalizedIp)) {
      return true;
    }

    byte[] addressBytes = parseIpBytes(normalizedIp);
    for (CidrBlock block : cidrBlocks) {
      if (block.matches(addressBytes)) {
        return true;
      }
    }
    return false;
  }

  private String normalizeIp(String raw) {
    try {
      return InetAddress.getByName(raw).getHostAddress();
    } catch (UnknownHostException ex) {
      throw new IllegalStateException("Invalid admin IP whitelist entry: " + raw, ex);
    }
  }

  private byte[] parseIpBytes(String ip) {
    try {
      return InetAddress.getByName(ip).getAddress();
    } catch (UnknownHostException ex) {
      throw new GatewayException(403, "admin_ip_not_allowed", "Admin access IP is invalid");
    }
  }

  private record CidrBlock(byte[] networkBytes, int prefixLength) {
    static CidrBlock parse(String cidr) {
      String[] parts = cidr.split("/");
      if (parts.length != 2) {
        throw new IllegalStateException("Invalid CIDR whitelist entry: " + cidr);
      }

      InetAddress networkAddress;
      try {
        networkAddress = InetAddress.getByName(parts[0].trim());
      } catch (UnknownHostException ex) {
        throw new IllegalStateException("Invalid CIDR network address: " + cidr, ex);
      }

      int prefix;
      try {
        prefix = Integer.parseInt(parts[1].trim());
      } catch (NumberFormatException ex) {
        throw new IllegalStateException("Invalid CIDR prefix length: " + cidr, ex);
      }

      int maxBits = networkAddress.getAddress().length * 8;
      if (prefix < 0 || prefix > maxBits) {
        throw new IllegalStateException("CIDR prefix out of range: " + cidr);
      }

      return new CidrBlock(networkAddress.getAddress(), prefix);
    }

    boolean matches(byte[] addressBytes) {
      if (addressBytes.length != networkBytes.length) {
        return false;
      }

      int fullBytes = prefixLength / 8;
      int remainingBits = prefixLength % 8;

      for (int i = 0; i < fullBytes; i++) {
        if (addressBytes[i] != networkBytes[i]) {
          return false;
        }
      }

      if (remainingBits == 0) {
        return true;
      }

      int mask = 0xFF << (8 - remainingBits);
      return (addressBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
    }
  }
}
