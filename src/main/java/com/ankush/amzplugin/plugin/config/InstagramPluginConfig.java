package com.ankush.amzplugin.plugin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "plugins.instagram")
@Component("instagramPluginConfig")
public class InstagramPluginConfig {
	private boolean enabled = false;

	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
