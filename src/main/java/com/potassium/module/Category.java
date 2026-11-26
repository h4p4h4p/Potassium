package com.potassium.module;

public enum Category {

    COMBAT("Combat"),
    EXPLOITS("Exploits"),
    MOVEMENT("Movement"),
    PLAYER("Player"),
    RENDER("Render"),
    CONFIGURATIONS("Configurations");

    private final String name;

    Category(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}