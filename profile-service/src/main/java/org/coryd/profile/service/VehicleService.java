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

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final ReplacedFiles replacedFiles;

    @Transactional
    public Vehicle register(String driverId, String callerId, VehicleRegistrationRequest request) {
        if (!callerId.equals(driverId)) {
            throw new UnauthorizedException("Only this driver can register their own vehicle");
        }
        String plate = normalizePlate(request.plateNumber());
        if (plateTaken(plate, null)) {
            throw new BadRequestException(PLATE_TAKEN);
        }
        Vehicle vehicle = Vehicle.builder()
                .driverId(driverId)
                .make(request.make())
                .model(request.model())
                .year(request.year())
                .color(request.color())
                .plateNumber(plate)
                .seatCapacity(request.seatCapacity())
                .exteriorPhotoUrl(request.exteriorPhotoUrl())
                .exteriorPhotoPublicId(request.exteriorPhotoPublicId())
                .interiorPhotoUrl(request.interiorPhotoUrl())
                .interiorPhotoPublicId(request.interiorPhotoPublicId())
                .hasAC(request.hasAC())
                .acConfirmedWorking(request.acConfirmedWorking())
                // A vehicle row only exists once registered, so it is never merely PENDING.
                .verificationStatus(VerificationStatus.IN_REVIEW)
                .submittedAt(Instant.now())
                .build();
        return vehicleRepository.save(vehicle);
    }

    /** Resubmission after a rejection (or any pre-approval edit) - resets verificationStatus to PENDING since the underlying facts may have changed. */
    @Transactional
    public Vehicle update(String vehicleId, String callerId, VehicleRegistrationRequest request) {
        Vehicle vehicle = findById(vehicleId);
        if (!callerId.equals(vehicle.getDriverId())) {
            throw new UnauthorizedException("Only this vehicle's driver can update it");
        }

        String plate = normalizePlate(request.plateNumber());
        if (plateTaken(plate, vehicle.getId())) {
            throw new BadRequestException(PLATE_TAKEN);
        }

        vehicle.setMake(request.make());
        vehicle.setModel(request.model());
        vehicle.setYear(request.year());
        vehicle.setColor(request.color());
        vehicle.setPlateNumber(plate);
        vehicle.setSeatCapacity(request.seatCapacity());
        replacedFiles.replaced(vehicle.getExteriorPhotoPublicId(), vehicle.getExteriorPhotoUrl(), request.exteriorPhotoPublicId());
        replacedFiles.replaced(vehicle.getInteriorPhotoPublicId(), vehicle.getInteriorPhotoUrl(), request.interiorPhotoPublicId());
        vehicle.setExteriorPhotoUrl(request.exteriorPhotoUrl());
        vehicle.setExteriorPhotoPublicId(request.exteriorPhotoPublicId());
        vehicle.setInteriorPhotoUrl(request.interiorPhotoUrl());
        vehicle.setInteriorPhotoPublicId(request.interiorPhotoPublicId());
        vehicle.setHasAC(request.hasAC());
        vehicle.setAcConfirmedWorking(request.acConfirmedWorking());
        if (vehicle.getVerificationStatus() != VerificationStatus.IN_REVIEW) {
            vehicle.setSubmittedAt(Instant.now());
            vehicle.setResubmitted(vehicle.getVerificationStatus() == VerificationStatus.REJECTED);
        }
        vehicle.setVerificationStatus(VerificationStatus.IN_REVIEW);
        vehicle.setRejectionReason(null);

        return vehicleRepository.save(vehicle);
    }

    static final String PLATE_TAKEN = "A vehicle with this plate number is already registered";

    /** Trimmed, single spaces - otherwise as the driver wrote it. */
    static String normalizePlate(String raw) {
        String plate = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (plateKey(plate).isEmpty()) {
            throw new BadRequestException("Enter the plate number as it appears on the car");
        }
        return plate;
    }

    /**
     * What makes two plates the same car: letters and digits only, so "LAG-123 AB" and "lag123ab" collide. The unique
     * plate column only catches an exact match; this catches a second registration in another spelling, and makes an
     * edit onto another car's plate a 400 rather than a database error.
     */
    static String plateKey(String plate) {
        return plate == null ? "" : plate.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private boolean plateTaken(String plate, String exceptVehicleId) {
        return vehicleRepository.existsByPlateKey(plateKey(plate), exceptVehicleId == null ? "" : exceptVehicleId);
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
