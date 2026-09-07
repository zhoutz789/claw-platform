package com.claw.server.domain.certificate;

import com.claw.server.common.api.BizException;
import com.claw.server.common.dto.CertificateDtos.CertificateDto;
import com.claw.server.domain.manufacturer.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 合格证服务（增量 B · R4/Q8）。
 *
 * <p>合格证在「生产完成」时即生成写库（不可补证）。补打（reprint）仅重新输出已存在的合格证，
 * 不会生成新的 cert_no（Q8 铁律）。合格证与设备 1:1（device_certificates.device_id UNIQUE）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CertificateService {

    private final CertificateRepository certificateRepository;
    private final ObjectMapper objectMapper;

    /** 出厂即生成合格证（Q8：合格证生成即写库，不可补证）。 */
    @Transactional
    public Certificate issue(Long deviceId, Long manufacturerId, Long operatorId, Product product) {
        if (certificateRepository.findByDeviceId(deviceId).isPresent()) {
            throw BizException.of(40911, "certificate.already.exists");
        }
        String certNo = "CERT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 20);
        Certificate c = Certificate.builder()
                .deviceId(deviceId)
                .certNo(certNo)
                .manufacturerId(manufacturerId)
                .productId(product == null ? null : product.getId())
                .issuedAt(Instant.now())
                .issuedBy(operatorId)
                .specJson(buildSpec(product, manufacturerId))
                .build();
        return certificateRepository.save(c);
    }

    @Transactional(readOnly = true)
    public Optional<Certificate> getByDevice(Long deviceId) {
        return certificateRepository.findByDeviceId(deviceId);
    }

    /** 设备合格证视图（含不可变快照 spec_json 与可编辑 data_json）。 */
    @Transactional(readOnly = true)
    public CertificateDto getDto(Long deviceId) {
        Certificate c = certificateRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> BizException.of(40401, "certificate.not.found"));
        return toDto(c);
    }

    /** 更新可编辑识别信息（data_json）；cert_no / issued_at / spec_json 保持不可变。 */
    @Transactional
    public CertificateDto updateData(Long deviceId, String dataJson) {
        Certificate c = certificateRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> BizException.of(40401, "certificate.not.found"));
        c.setDataJson(dataJson);
        return toDto(certificateRepository.save(c));
    }

    private CertificateDto toDto(Certificate c) {
        return new CertificateDto(c.getId(), c.getDeviceId(), c.getCertNo(), c.getManufacturerId(),
                c.getProductId(), c.getSpecJson(), c.getDataJson(), c.getIssuedBy(), c.getIssuedAt());
    }

    @Transactional(readOnly = true)
    public Optional<Certificate> getByCertNo(String certNo) {
        return certificateRepository.findByCertNo(certNo);
    }

    /** 补打（Q8：仅重新输出已存在的合格证，不生成新 cert_no）。 */
    @Transactional(readOnly = true)
    public Certificate reprint(Long deviceId) {
        return certificateRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> BizException.of(40401, "certificate.not.found"));
    }

    private String buildSpec(Product product, Long manufacturerId) {
        try {
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("manufacturerId", manufacturerId);
            if (product != null) {
                spec.put("productId", product.getId());
                spec.put("productName", product.getName());
                spec.put("assetType", product.getAssetType() == null ? null : product.getAssetType().name());
                spec.put("model", product.getModel());
            }
            spec.put("issuedAt", Instant.now().toString());
            return objectMapper.writeValueAsString(spec);
        } catch (Exception e) {
            return "{}";
        }
    }
}
