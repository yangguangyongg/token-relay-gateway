package com.tokenrelay.gateway.service;

public class GatewayException extends RuntimeException {
  private final int status;
  private final String code;

  public GatewayException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}
