package com.blooddonor.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A generated setting that must survive restarts (e.g. the VAPID key pair for Web Push). */
@Entity
@Table(name = "app_settings")
@Getter @Setter @NoArgsConstructor
public class AppSetting {

    @Id
    @Column(name = "setting_key", length = 60)
    private String key;

    @Column(name = "setting_value", nullable = false, length = 2000)
    private String value;

    public AppSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }
}
