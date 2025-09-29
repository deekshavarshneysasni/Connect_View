package com.example.backend.model;

public class ExternalAuthRequest {
        private String username;
        private String password;
        private String user_type;

        public ExternalAuthRequest(String username, String password, String user_type) {
            this.username = username;
            this.password = password;
            this.user_type = user_type;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

    public String getuser_type() {
        return user_type;
    }


    }
