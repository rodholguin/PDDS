package com.tasfb2b.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "global_sequence")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GlobalSequence {

    @Id
    @Column(name = "sequence_name", nullable = false, length = 64)
    private String sequenceName;

    @Column(name = "next_value", nullable = false)
    private Long nextValue;
}
