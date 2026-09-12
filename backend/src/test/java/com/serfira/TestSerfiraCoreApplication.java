package com.serfira;

import org.springframework.boot.SpringApplication;

public class TestSerfiraCoreApplication {

	public static void main(String[] args) {
		SpringApplication.from(SerfiraCoreApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
