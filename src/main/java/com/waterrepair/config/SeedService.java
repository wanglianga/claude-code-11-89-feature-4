package com.waterrepair.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waterrepair.auth.AuthService;
import com.waterrepair.auth.UserAccount;
import com.waterrepair.auth.UserAccountRepository;
import com.waterrepair.crew.Crew;
import com.waterrepair.crew.CrewRepository;
import com.waterrepair.crew.OperationPermit;
import com.waterrepair.crew.OperationPermitRepository;
import com.waterrepair.crew.ToolItem;
import com.waterrepair.crew.ToolItemRepository;
import com.waterrepair.crew.Vehicle;
import com.waterrepair.crew.VehicleRepository;
import com.waterrepair.compensation.CompensationSeedDtos;
import com.waterrepair.compensation.ContractLevel;
import com.waterrepair.compensation.ContractLevelRepository;
import com.waterrepair.compensation.ServiceAccount;
import com.waterrepair.compensation.ServiceAccountRepository;
import com.waterrepair.network.AddressPoint;
import com.waterrepair.network.AddressPointRepository;
import com.waterrepair.network.GeoLandmark;
import com.waterrepair.network.GeoLandmarkRepository;
import com.waterrepair.network.NetworkNode;
import com.waterrepair.network.NetworkNodeRepository;
import com.waterrepair.network.PipeSegment;
import com.waterrepair.network.PipeSegmentRepository;
import com.waterrepair.network.PressureDevice;
import com.waterrepair.network.PressureDeviceRepository;
import com.waterrepair.network.PressureReading;
import com.waterrepair.network.PressureReadingRepository;
import com.waterrepair.network.ValveSpec;
import com.waterrepair.network.ValveSpecRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 载入随项目提供的模拟管网/班组/账号样本；reset 时清空 public 模式全部业务表后重播。
 */
@Service
public class SeedService {

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final AuthService authService;
    private final UserAccountRepository userRepo;
    private final NetworkNodeRepository nodeRepo;
    private final PipeSegmentRepository segmentRepo;
    private final ValveSpecRepository valveRepo;
    private final AddressPointRepository addressRepo;
    private final GeoLandmarkRepository landmarkRepo;
    private final PressureDeviceRepository deviceRepo;
    private final PressureReadingRepository readingRepo;
    private final CrewRepository crewRepo;
    private final VehicleRepository vehicleRepo;
    private final ToolItemRepository toolRepo;
    private final OperationPermitRepository permitRepo;
    private final ContractLevelRepository contractLevelRepo;
    private final ServiceAccountRepository serviceAccountRepo;

    public SeedService(ObjectMapper objectMapper, JdbcTemplate jdbcTemplate, AuthService authService,
                       UserAccountRepository userRepo, NetworkNodeRepository nodeRepo,
                       PipeSegmentRepository segmentRepo, ValveSpecRepository valveRepo,
                       AddressPointRepository addressRepo, GeoLandmarkRepository landmarkRepo,
                       PressureDeviceRepository deviceRepo, PressureReadingRepository readingRepo,
                       CrewRepository crewRepo, VehicleRepository vehicleRepo,
                       ToolItemRepository toolRepo, OperationPermitRepository permitRepo,
                       ContractLevelRepository contractLevelRepo,
                       ServiceAccountRepository serviceAccountRepo) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.authService = authService;
        this.userRepo = userRepo;
        this.nodeRepo = nodeRepo;
        this.segmentRepo = segmentRepo;
        this.valveRepo = valveRepo;
        this.addressRepo = addressRepo;
        this.landmarkRepo = landmarkRepo;
        this.deviceRepo = deviceRepo;
        this.readingRepo = readingRepo;
        this.crewRepo = crewRepo;
        this.vehicleRepo = vehicleRepo;
        this.toolRepo = toolRepo;
        this.permitRepo = permitRepo;
        this.contractLevelRepo = contractLevelRepo;
        this.serviceAccountRepo = serviceAccountRepo;
    }

    @Transactional
    public void seedIfEmpty() {
        if (userRepo.count() > 0) {
            return;
        }
        doSeed();
    }

    @Transactional
    public Map<String, Object> reset() {
        List<String> tables = jdbcTemplate.queryForList(
                "select tablename from pg_tables where schemaname = 'public'", String.class);
        if (!tables.isEmpty()) {
            String joined = tables.stream().map(t -> "public." + t)
                    .reduce((a, b) -> a + "," + b).orElseThrow();
            jdbcTemplate.execute("truncate table " + joined + " restart identity cascade");
        }
        doSeed();
        return counts();
    }

    private void doSeed() {
        SeedDtos.NetworkSeed network = readJson("samples/network.json", SeedDtos.NetworkSeed.class);
        for (SeedDtos.NodeDto n : network.nodes()) {
            nodeRepo.save(new NetworkNode(n.code(), n.name(), n.type(), n.lng(), n.lat()));
        }
        for (SeedDtos.SegmentDto s : network.segments()) {
            segmentRepo.save(new PipeSegment(s.code(), s.name(), s.from(), s.to(),
                    s.pMin(), s.pMax(), s.tolSec()));
        }
        for (SeedDtos.ValveDto v : network.valves()) {
            valveRepo.save(new ValveSpec(v.code(), v.name(), v.nodeCode(),
                    v.qualification(), v.permit()));
        }
        for (SeedDtos.AddressDto a : network.addresses()) {
            addressRepo.save(new AddressPoint(a.code(), a.address(), a.segment(),
                    a.building(), a.lng(), a.lat(), a.aliases()));
        }
        for (SeedDtos.LandmarkDto l : network.landmarks()) {
            landmarkRepo.save(new GeoLandmark(l.code(), l.name(), l.lng(), l.lat(),
                    l.candidateSegments()));
        }
        for (SeedDtos.DeviceDto d : network.devices()) {
            deviceRepo.save(new PressureDevice(d.deviceNo(), d.name(), d.segment()));
        }
        for (SeedDtos.ReadingDto r : network.readings()) {
            readingRepo.save(new PressureReading(r.code(), r.deviceNo(), r.segment(),
                    r.pressureMpa(), r.sampledAt(),
                    r.clockOffsetSeconds() == null ? 0 : r.clockOffsetSeconds(), r.note()));
        }

        SeedDtos.CrewsSeed crews = readJson("samples/crews.json", SeedDtos.CrewsSeed.class);
        for (SeedDtos.VehicleDto v : crews.vehicles()) {
            vehicleRepo.save(new Vehicle(v.code(), v.name(), v.plateNo(),
                    !Boolean.FALSE.equals(v.available()), v.unavailableReason()));
        }
        for (SeedDtos.ToolDto t : crews.tools()) {
            toolRepo.save(new ToolItem(t.code(), t.name(),
                    !Boolean.FALSE.equals(t.available()), t.unavailableReason()));
        }
        for (SeedDtos.PermitDto p : crews.permits()) {
            permitRepo.save(new OperationPermit(p.code(), p.name(), p.valveCode()));
        }
        for (SeedDtos.CrewDto c : crews.crews()) {
            crewRepo.save(new Crew(c.code(), c.name(),
                    LocalTime.parse(c.shiftStart()), LocalTime.parse(c.shiftEnd()),
                    c.contactPhone(), !Boolean.FALSE.equals(c.active()),
                    c.primaryVehicleCode(), c.qualificationCodes(), c.standardToolCodes()));
        }

        SeedDtos.AccountsSeed accounts = readJson("samples/accounts.json", SeedDtos.AccountsSeed.class);
        for (SeedDtos.AccountDto a : accounts.accounts()) {
            userRepo.save(new UserAccount(a.username(), authService.encode(a.password()),
                    a.displayName(), a.role(), a.crewCode()));
        }

        CompensationSeedDtos.CompensationSeed comp = readJson(
                "samples/compensation.json", CompensationSeedDtos.CompensationSeed.class);
        for (CompensationSeedDtos.LevelSeed l : comp.contractLevels()) {
            contractLevelRepo.save(new ContractLevel(l.code(), l.name(),
                    l.creditAmountCny(), l.slaNote()));
        }
        for (CompensationSeedDtos.AccountSeed a : comp.serviceAccounts()) {
            serviceAccountRepo.save(new ServiceAccount(a.accountNo(), a.customerName(),
                    a.contractLevelCode(), a.addressCodes(), a.phone()));
        }
    }

    private <T> T readJson(String path, Class<T> type) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readValue(in, type);
        } catch (Exception ex) {
            throw new IllegalStateException("无法读取样本文件 " + path + ": " + ex.getMessage(), ex);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> counts() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accounts", userRepo.count());
        m.put("nodes", nodeRepo.count());
        m.put("segments", segmentRepo.count());
        m.put("valves", valveRepo.count());
        m.put("addresses", addressRepo.count());
        m.put("landmarks", landmarkRepo.count());
        m.put("devices", deviceRepo.count());
        m.put("readings", readingRepo.count());
        m.put("crews", crewRepo.count());
        m.put("vehicles", vehicleRepo.count());
        m.put("tools", toolRepo.count());
        m.put("permits", permitRepo.count());
        m.put("contractLevels", contractLevelRepo.count());
        m.put("serviceAccounts", serviceAccountRepo.count());
        return m;
    }
}
