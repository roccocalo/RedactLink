package com.roccocalo.redactlink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RedactlinkApplication {

	public static void main(String[] args) {
		SpringApplication.run(RedactlinkApplication.class, args);
	}

}
