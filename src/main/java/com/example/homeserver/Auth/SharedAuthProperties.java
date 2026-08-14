package com.example.homeserver.Auth;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
@Validated @ConfigurationProperties(prefix = "shared-auth")
public class SharedAuthProperties {
    @NotBlank @Size(min = 32) private String secret;
    private String cookieName = "MEDIA_AUTH";
    private Duration tokenTtl = Duration.ofMinutes(30);
    private boolean cookieSecure;
    private int imagePort = 8081;
    private String imageUrl = "";
    public String getSecret(){return secret;} public void setSecret(String value){secret=value;}
    public String getCookieName(){return cookieName;} public void setCookieName(String value){cookieName=value;}
    public Duration getTokenTtl(){return tokenTtl;} public void setTokenTtl(Duration value){tokenTtl=value;}
    public boolean isCookieSecure(){return cookieSecure;} public void setCookieSecure(boolean value){cookieSecure=value;}
    public int getImagePort(){return imagePort;} public void setImagePort(int value){imagePort=value;}
    public String getImageUrl(){return imageUrl;} public void setImageUrl(String value){imageUrl=value;}
}
