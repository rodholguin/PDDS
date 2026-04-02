package com.tasfb2b.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "data_import_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DataImportLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt;

    @Column(name = "total_rows", nullable = false)
    @Builder.Default
    private Integer totalRows = 0;

    @Column(name = "success_rows", nullable = false)
    @Builder.Default
    private Integer successRows = 0;

    @Column(name = "error_rows", nullable = false)
    @Builder.Default
    private Integer errorRows = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private ImportStatus status = ImportStatus.SUCCESS;

    /**
     * Detalle de errores de fila (puede ser largo; se almacena como TEXT).
     */
    @Column(name = "error_details", columnDefinition = "TEXT")
    private String errorDetails;

    @PrePersist
    private void setImportedAt() {
        if (importedAt == null) importedAt = LocalDateTime.now();
    }
}
