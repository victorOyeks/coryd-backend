package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.profile.dto.VehicleRegistrationRequest;
import org.coryd.profile.dto.VehicleSummary;
import org.coryd.profile.dto.VerificationDecisionRequest;
import org.coryd.profile.entity.Vehicle;
import org.coryd.profile.repository.VehicleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final CloudinaryService cloudinaryService;
    private final VerificationAggregationService verificationAggregationService;

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
                .acPhotoUrl(request.acPhotoUrl())
                .acPhotoPublicId(request.acPhotoPublicId())
                .hasAC(request.hasAC())
                .acConfirmedWorking(request.acConfirmedWorking())
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
        vehicle.setAcPhotoUrl(request.acPhotoUrl());
        vehicle.setAcPhotoPublicId(request.acPhotoPublicId());
        vehicle.setHasAC(request.hasAC());
        vehicle.setAcConfirmedWorking(request.acConfirmedWorking());
        vehicle.setVerificationStatus(VerificationStatus.PENDING);
        vehicle.setRejectionReason(null);

        return vehicleRepository.save(vehicle);
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

    @Transactional
    public Vehicle decide(String vehicleId, UserRole callerRole, VerificationDecisionRequest request) {
        if (callerRole != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("Only an ops admin can decide vehicle verification");
        }
        Vehicle vehicle = findById(vehicleId);

        vehicle.setVerificationStatus(request.status());
        vehicle.setRejectionReason(request.status() == VerificationStatus.REJECTED ? request.rejectionReason() : null);

        if (request.status() == VerificationStatus.APPROVED) {
            // Verification photos are only ever needed for this review - nothing
            // rider-facing surfaces them - so free the storage once a decision is made.
            // Best-effort: a flaky Cloudinary call must never block the approval itself.
            deleteVerificationPhoto(vehicle.getExteriorPhotoPublicId());
            deleteVerificationPhoto(vehicle.getInteriorPhotoPublicId());
            deleteVerificationPhoto(vehicle.getAcPhotoPublicId());
            vehicle.setExteriorPhotoUrl(null);
            vehicle.setExteriorPhotoPublicId(null);
            vehicle.setInteriorPhotoUrl(null);
            vehicle.setInteriorPhotoPublicId(null);
            vehicle.setAcPhotoUrl(null);
            vehicle.setAcPhotoPublicId(null);
        }

        vehicleRepository.save(vehicle);
        verificationAggregationService.reevaluateAndPublish(vehicle.getDriverId(), request.reviewedBy());
        return vehicle;
    }

    private void deleteVerificationPhoto(String publicId) {
        if (publicId == null) {
            return;
        }
        try {
            cloudinaryService.delete(publicId, "image");
        } catch (Exception e) {
            log.warn("Could not delete verification photo from Cloudinary, leaving it orphaned: {}", e.getMessage());
        }
    }
}
