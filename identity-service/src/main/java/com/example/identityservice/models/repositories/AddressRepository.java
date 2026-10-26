package com.example.identityservice.models.repositories;

import com.example.identityservice.models.dto.res.InternalAddressRes;
import com.example.identityservice.models.entities.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AddressRepository extends JpaRepository<Address, UUID> {

    List<Address> findAllByUserIdAndDeletedAtIsNull(UUID userId);

    Optional<Address> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    Optional<Address> findByUserIdAndIsDefaultTrueAndDeletedAtIsNull(UUID userId);

    @Query("""
            SELECT new com.example.identityservice.models.dto.res.InternalAddressRes(
                a.id,
                a.user.id,
                a.recipientName,
                a.phone,
                a.addressLine1,
                a.addressLine2,
                a.district,
                a.city,
                a.country
            )
            FROM Address a
            WHERE a.id = :addressId
              AND a.user.id = :userId
              AND a.deletedAt IS NULL
              AND a.user.deletedAt IS NULL
            """)
    Optional<InternalAddressRes> findInternalAddressByIdAndUserId(
            @Param("addressId") UUID addressId,
            @Param("userId") UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Address a SET a.isDefault = false WHERE a.user.id = :userId")
    void clearDefaultAddresses(@Param("userId") UUID userId);
}
