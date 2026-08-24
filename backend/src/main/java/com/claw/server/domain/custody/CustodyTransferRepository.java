package com.claw.server.domain.custody;

import com.claw.server.common.enums.TransferType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustodyTransferRepository extends JpaRepository<CustodyTransfer, Long> {

    List<CustodyTransfer> findByAssetIdAndDeletedFalseOrderByTransferredAtDesc(Long assetId);

    List<CustodyTransfer> findByToUserIdAndDeletedFalse(Long toUserId);

    List<CustodyTransfer> findByFromUserIdAndDeletedFalse(Long fromUserId);

    CustodyTransfer findFirstByAssetIdAndDeletedFalseOrderByTransferredAtDesc(Long assetId);
}
