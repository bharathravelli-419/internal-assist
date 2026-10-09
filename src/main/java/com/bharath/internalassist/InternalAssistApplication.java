package com.bharath.internalassist;

import com.bharath.internalassist.tenant.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class InternalAssistApplication {

	public static void main(String[] args) {
		SpringApplication.run(InternalAssistApplication.class, args);
	}

}
