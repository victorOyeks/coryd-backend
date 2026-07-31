package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.DriverApprovedEvent;
import org.coryd.common.kafka.events.DriverRejectedEvent;
import org.coryd.profile.dto.VehicleRegistrationRequest;
import org.coryd.profile.dto.VehicleSummary;
import org.coryd.profile.dto.VerificationDecisionRequest;
import org.coryd.profile.entity.Vehicle;
import org.coryd.profile.repository.VehicleRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Transactional
    public Vehicle register(String driverId, VehicleRegistrationRequest request) {
        if (vehicleRepository.existsByPlateNumber(request.plateNumber())) {
            throw new BadRequestException("A vehicle with this plate number is already registered");
        }
        Vehicle vehicle = Vehicle.builder()
                .driverId(driverId)
                .make(request.make())
                .model(request.model())
                .year(request.year())
                .plateNumber(request.plateNumber())
                .seatCapacity(request.seatCapacity())
                .exteriorPhotoUrl(request.exteriorPhotoUrl())
                .interiorPhotoUrl(request.interiorPhotoUrl())
                .acPhotoUrl(request.acPhotoUrl())
                .hasAC(request.hasAC())
                .build();
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
    public Vehicle decide(String vehicleId, VerificationDecisionRequest request) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found"));

        vehicle.setVerificationStatus(request.status());
        vehicle.setRejectionReason(request.status() == VerificationStatus.REJECTED ? request.rejectionReason() : null);
        vehicleRepository.save(vehicle);

        if (request.status() == VerificationStatus.APPROVED) {
            kafkaTemplate.send(KafkaTopics.DRIVER_APPROVED, DriverApprovedEvent.builder()
                    .driverId(vehicle.getDriverId().toString())
                    .vehicleId(vehicle.getId().toString())
                    .reviewedBy(request.reviewedBy())
                    .approvedAt(LocalDateTime.now())
                    .build());
        } else if (request.status() == VerificationStatus.REJECTED) {
            kafkaTemplate.send(KafkaTopics.DRIVER_REJECTED, DriverRejectedEvent.builder()
                    .driverId(vehicle.getDriverId().toString())
                    .vehicleId(vehicle.getId().toString())
                    .rejectionReason(request.rejectionReason())
                    .reviewedBy(request.reviewedBy())
                    .rejectedAt(LocalDateTime.now())
                    .build());
        }

        return vehicle;
    }
}
