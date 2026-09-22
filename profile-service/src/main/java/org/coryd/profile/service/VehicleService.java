package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.profile.dto.VehicleRegistrationRequest;
import org.coryd.profile.dto.VehicleSummary;
import org.coryd.profile.entity.Vehicle;
import org.coryd.profile.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final PremblyVerificationService premblyVerificationService;

    @Transactional
    public Vehicle register(String driverId, String callerId, VehicleRegistrationRequest request) {
        if (!callerId.equals(driverId)) {
            throw new UnauthorizedException("Only this driver can register their own vehicle");
        }
        if (vehicleRepository.existsByPlateNumber(request.plateNumber())) {
            throw new BadRequestException("A vehicle with this plate number is already registered");
        }
        Vehicle vehicle = Vehicle.builder()
                .driverId(driverId)
                .make(request.make())
                .model(request.model())
                .year(request.year())
                .color(request.color())
                .plateNumber(request.plateNumber())
                .seatCapacity(request.seatCapacity())
                .exteriorPhotoUrl(request.exteriorPhotoUrl())
                .exteriorPhotoPublicId(request.exteriorPhotoPublicId())
                .interiorPhotoUrl(request.interiorPhotoUrl())
                .interiorPhotoPublicId(request.interiorPhotoPublicId())
                .hasAC(request.hasAC())
                .acConfirmedWorking(request.acConfirmedWorking())
                // A vehicle row only exists once registered, so it is never merely PENDING.
                .verificationStatus(VerificationStatus.IN_REVIEW)
                .build();
        Vehicle saved = vehicleRepository.save(vehicle);
        premblyVerificationService.checkPlateNumber(
                saved.getDriverId(), saved.getPlateNumber(), saved.getMake(), saved.getColor());
        return saved;
    }

    /** Resubmission after a rejection (or any pre-approval edit) - resets verificationStatus to PENDING since the underlying facts may have changed. */
    @Transactional
    public Vehicle update(String vehicleId, String callerId, VehicleRegistrationRequest request) {
        Vehicle vehicle = findById(vehicleId);
        if (!callerId.equals(vehicle.getDriverId())) {
            throw new UnauthorizedException("Only this vehicle's driver can update it");
        }

        vehicle.setMake(request.make());
        vehicle.setModel(request.model());
        vehicle.setYear(request.year());
        vehicle.setColor(request.color());
        vehicle.setPlateNumber(request.plateNumber());
        vehicle.setSeatCapacity(request.seatCapacity());
        vehicle.setExteriorPhotoUrl(request.exteriorPhotoUrl());
        vehicle.setExteriorPhotoPublicId(request.exteriorPhotoPublicId());
        vehicle.setInteriorPhotoUrl(request.interiorPhotoUrl());
        vehicle.setInteriorPhotoPublicId(request.interiorPhotoPublicId());
        vehicle.setHasAC(request.hasAC());
        vehicle.setAcConfirmedWorking(request.acConfirmedWorking());
        vehicle.setVerificationStatus(VerificationStatus.IN_REVIEW);
        vehicle.setRejectionReason(null);

        Vehicle saved = vehicleRepository.save(vehicle);
        premblyVerificationService.checkPlateNumber(
                saved.getDriverId(), saved.getPlateNumber(), saved.getMake(), saved.getColor());
        return saved;
    }

    public List<Vehicle> findByDriver(String driverId) {
        return vehicleRepository.findByDriverId(driverId);
    }

    public Vehicle findById(String vehicleId) {
        return vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found"));
    }

    /** Batch lookup for search-result enrichment (trip-service) - one call per result page, not one per vehicle. */
    public List<VehicleSummary> findSummariesByIds(List<String> vehicleIds) {
        return vehicleRepository.findAllById(vehicleIds).stream()
                .map(VehicleSummary::from)
                .toList();
    }

}
