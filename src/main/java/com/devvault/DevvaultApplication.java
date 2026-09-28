package com.devvault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DevvaultApplication {

	public static void main(String[] args) {
		SpringApplication.run(DevvaultApplication.class, args);
	}

}
