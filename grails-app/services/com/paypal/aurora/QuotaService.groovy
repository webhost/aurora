package com.paypal.aurora

import com.paypal.aurora.model.Flavor
import com.paypal.aurora.model.Instance
import com.paypal.aurora.model.Quota
import com.paypal.aurora.model.QuotaUsage
import org.apache.commons.lang.WordUtils

class QuotaService {

    private static final String OS_QUOTAS = 'os-quota-sets'
    public static final String GIGABYTES = "gigabytes"
    public static final String VOLUMES = "volumes"
    public static final String CORES = "cores"
    public static final String RAM = "ram"
    public static final String INSTANCES = "instances"
    public static final String DISK = "Disk space (Volumes x Gigabytes)"

    def openStackRESTService
    def sessionStorageService
    def instanceService
    def flavorService


    def getAllQuotas(def tenantId = null) {
        getQuotasByTenantId(sessionStorageService.tenant.id)
    }

    Quota[] getQuotasByTenantId(def tenantId) {
        def resp = openStackRESTService.get(service, OS_QUOTAS + "/${tenantId}")
        def quotas = []
        for (quota in resp.quota_set) {
            if (quota.key != "id") {
                quotas << new Quota(quota)
            }
        }
        if (!isFolsom()) {
            resp = openStackRESTService.get(openStackRESTService.NOVA, OS_QUOTAS + "/${tenantId}")
            for (quota in resp.quota_set) {
                if (quota.key != "id") {
                    quotas << new Quota(quota)
                }
            }
        }
        quotas.sort { it.displayName as String }
    }

    private boolean isFolsom() {
        !sessionStorageService.openStackVersion || sessionStorageService.openStackVersion.equalsIgnoreCase('folsom')
    }

    def setQuotasByTenantId(def quotas, def tenantId) {
        if (!isFolsom()) {
            def quotaSetGrizzlyNova = [:]
            for (Quota quota in quotas) {
                quotaSetGrizzlyNova[quota.name] = quota.limit
            }
            quotaSetGrizzlyNova.remove('volumes')
            quotaSetGrizzlyNova.remove('gigabytes')
            quotaSetGrizzlyNova.remove('snapshots')

            openStackRESTService.put(openStackRESTService.NOVA, OS_QUOTAS + "/${tenantId}", null, [quota_set: quotaSetGrizzlyNova])
        }        
        def quotaSet = [:]
        for (Quota quota in quotas) {
            quotaSet[quota.name] = quota.limit
        }
        openStackRESTService.put(service, OS_QUOTAS + "/${tenantId}", null, [quota_set: quotaSet])
    }

    private String getService() {
        if (isFolsom()) {
            return openStackRESTService.NOVA
        } else {
            return openStackRESTService.NOVA_VOLUME
        }
    }

    List<QuotaUsage> getQuotaUsage(def tenantId) {
        List<Instance> instanceList = instanceService.listAll()
        Map<String, Flavor> flavorMap = getFlavorMap()
        return getQuotaUsageList(getQuotasByTenantId(tenantId), flavorMap, instanceList)
    }

    Quota getQuotaByName(String name) {
        for (Quota quota : getAllQuotas()) {
            if (quota.name.equals(name)) {
                return quota
            }
        }
        Quota quota = new Quota()
        quota.name = name
        quota.limit = 0
        quota.addDisplayName();
        return quota
    }

    private Map<String, Flavor> getFlavorMap() {
        List<Flavor> flavorList = flavorService.listAll();
        Map<String, Flavor> flavorMap = new HashMap<>()
        for (flavor in flavorList) {
            flavorMap.put(flavor.id, flavor)
        }
        return flavorMap
    }

    private List<QuotaUsage> getQuotaUsageList(Quota[] quota, Map<String, Flavor> flavorMap, List<Instance> instanceList) {
        int cpu = 0
        int ram = 0
        int disk = 0
        for (Instance instance in instanceList) {
            Flavor flavor = flavorMap.get(instance.flavorId)
            if (flavor) {
                cpu += flavor.vcpu.toInteger()
                ram += flavor.memory.toInteger()
                disk += flavor.disk.toInteger()
            }
        }
        return fillQuotaUsageList(quota, disk, cpu, ram, instanceList.size())
    }

    private List<QuotaUsage> fillQuotaUsageList(Quota[] quota, int disk, int cpu, int ram, int instances) {
        List<QuotaUsage> quotaUsageList = new LinkedList<>()
        Integer diskQuota = 0
        Integer volumes = 0
        for (int i = 0; i < quota.length; i++) {
            Integer limit = quota[i].limit.toInteger()

            switch (quota[i].name) {
                case GIGABYTES:
                    diskQuota = limit
                    break

                case VOLUMES:
                    volumes = limit
                    break

                case CORES:
                    quotaUsageList.add(new QuotaUsage(WordUtils.capitalizeFully(CORES), limit, cpu))
                    break

                case INSTANCES:
                    quotaUsageList.add(new QuotaUsage(WordUtils.capitalizeFully(INSTANCES), limit, instances))
                    break

                case RAM:
                    quotaUsageList.add(new QuotaUsage(WordUtils.capitalizeFully(RAM), limit, ram))
                    break
            }
        }
        quotaUsageList.add(new QuotaUsage(DISK, diskQuota * volumes, disk))
        return quotaUsageList
    }
}
