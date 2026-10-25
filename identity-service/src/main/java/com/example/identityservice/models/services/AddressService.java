package com.example.identityservice.models.services;

import com.example.identityservice.models.dto.req.AddressReq;
import com.example.identityservice.models.dto.res.AddressRes;
import com.example.identityservice.models.dto.res.InternalAddressRes;

import java.util.List;
import java.util.UUID;

public interface AddressService {

    List<AddressRes> getUserAddresses(UUID userId);

    AddressRes getAddressById(UUID userId, UUID addressId);

    InternalAddressRes getInternalAddress(UUID userId, UUID addressId);

    AddressRes createAddress(UUID userId, AddressReq req);

    AddressRes updateAddress(UUID userId, UUID addressId, AddressReq req);

    void deleteAddress(UUID userId, UUID addressId);

    AddressRes setDefaultAddress(UUID userId, UUID addressId);
}
