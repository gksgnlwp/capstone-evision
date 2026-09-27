package com.evision.common.code;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공통 코드표. CHARGER_STAT 그룹은 normalized_status로 정규화 상태를 가진다 (OP-11).
 */
@Entity
@Table(name = "code_dictionary")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CodeDictionary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "code_id")
    private Long id;

    @Column(name = "code_group", nullable = false, length = 30)
    private String codeGroup;

    @Column(name = "code", nullable = false, length = 10)
    private String code;

    @Column(name = "code_name", nullable = false, length = 100)
    private String codeName;

    @Column(name = "normalized_status", length = 12)
    private String normalizedStatus;

    @Column(name = "source_doc", nullable = false, length = 100)
    private String sourceDoc;
}
