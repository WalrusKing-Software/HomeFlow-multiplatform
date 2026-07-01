# Home Server Project Planning

This document outlines the plan for building a dedicated home server using a custom PC build to host personal media, web apps, and other self-hosted services.

**PC Part Picker Link**: []()

## Current Setup

- Jellyfin media server currently running on personal PC
- Single web app hosted on Raspberry Pi 5 on local network
- Remote access via Tailscale when off home network
- Goal: migrate all hosted services off personal PC and Pi onto dedicated server hardware

## Remote Access Plan

- Continue using Tailscale for remote access to homelab services
- Nginx Proxy Manager for internal routing of web apps and services
- Optional future addition: Cloudflare Tunnel for public-facing web apps without exposing home IP

## Software Stack

### Hypervisor / OS
- Proxmox VE (bare metal hypervisor, runs VMs and LXC containers)

### Services to Run
- Jellyfin — media library and streaming server
- TBD — music library and streaming server
- TBD — Docker container management UI 
- TBD — reverse proxy for web apps and services
- Tailscale — remote access VPN
- Personal web apps (self-hosted)

## Power Considerations

- Target idle power draw: under 65W
- Avoid enterprise server hardware due to high idle power consumption
- Modern consumer CPUs (Ryzen 5 / Intel i5) offer much better performance-per-watt
- UPS should cover server + networking gear for at least 10–15 minutes runtime

## Physical Location Notes

- Placement TBD — tower case fits anywhere, no rack space required
- Noise should be minimal with consumer hardware and standard case fans
- Can revisit rack setup in the future if build grows to need it

## Raspberry Pi 5 Role

- Currently hosting one local web app — to be migrated to main server
- Possible future uses: Pi-hole DNS ad blocker, low-power always-on node, network monitor

## Current Hardware

### Hardware not in Homelab PC
- Raspberry Pi 5 8GB RAM
- 1TB m.2 drive
- GLiNet Flint 2 GL-MT6000 Router
- 2x WD GreenPower 1TB (WD10EURX)
- 1x WD Blue 1TB (WD10EZEX, 64MB cache)
- 1x Seagate Desktop HDD 1TB (ST1000DM003)
- 1x Seagate Barracuda LP 1TB (ST31000520AS)
- 1x Seagate Barracuda 500GB (ST500DM002)
- HP Smart Array P400 RAID controller w/ 512MB Battery-Backed Write Cache
- 4x SFF-8087 SAS cables (Amphenol)
- ATI FirePro 3D workstation GPU
- HDD mounting trays/cages

### Homelab PC Hardware

#### CPU
[AMD Ryzen 5 9600X](https://www.newegg.com/amd-ryzen-5-9000-series-ryzen-5-9600x-granite-ridge-socket-am5-desktop-cpu-processor/p/N82E16819113844?Item=N82E16819113844)

#### Motherboard
[MSI MAG B850 TOMAHAWK MAX](https://www.newegg.com/msi-mag-b850-tomahawk-max-wifi-atx-motherboard-amd-b850-am5/p/N82E16813144697?Item=N82E16813144697)

#### CPU Cooler
[Cooler Master Hyper 212 Black CPU Air Cooler](https://www.newegg.com/cooler-master-hyper-212-black-120mm-intel-lga-1700-1200-1151-1150-1155-1156-amd-am5-am4/p/N82E16835103364?Item=N82E16835103364)

#### RAM
[TeamGroup T-Force Vulcan 16GB (2x8GB) RAM DDR5 6000MHz](https://www.ebay.com/itm/198434504243)

#### Storage
[Seagate EXOS Enterprise 4 TB 3.5" 7200 RPM](https://pcpartpicker.com/product/J2KcCJ/seagate-exos-enterprise-4-tb-35-7200rpm-internal-hard-drive-st4000nm0035) x2

#### Power Supply
[MSI MAG A850GL PCIE5 850 W 80+ Gold](https://pcpartpicker.com/product/zF4Zxr/msi-mag-a850gl-pcie5-850-w-80-gold-certified-fully-modular-atx-power-supply-mag-a850gl-pcie5)

#### Case
[Rosewill THOR NAS](https://www.newegg.com/rosewill-thor-nas-black/p/N82E16811147371?Item=N82E16811147371)


---

## Desired Hardware

Below is the hardware that I am looking for. Each section contains requirements and options found.

### Storage Drives
- expansion: min of 10TB usable storage 

**Options**:
- [Seagate EXOS Enterprise 4 TB 3.5" 7200 RPM](https://pcpartpicker.com/product/J2KcCJ/seagate-exos-enterprise-4-tb-35-7200rpm-internal-hard-drive-st4000nm0035)
  - $173.00

### GPU (Future)
- For Jellyfin hardware transcoding via NVENC
- Target: NVIDIA GTX 1660 Super, RTX 3060, or Quadro P2000

**Options**:
- [ASUS GeForce RTX 2080Ti 11GB GDDR6](https://www.ebay.com/itm/327222401487?_skw=gpu&epid=9014158045&itmmeta=01KVHGRZN5VVN4G9DTXPCFH5HX&hash=item4c2ff999cf%3Ag%3AhVYAAeSwkDBqNVjL&itmprp=enc%3AAQALAAAAwGfYFPkwiKCW4ZNSs2u11xBRz69ncWmkxisvG9MUtEJk3BR%2BCbIohiC8JerFFwEoB3UMSaIE2QxYZfP1KMFNnRiZe%2B2jC7pN3RHg0SCKje2k0Gxk0j6v0zBVoRlVc%2BMORaX%2BI7JLrzsb1b1qsj9EV1tBvIi0mWVdLaESiPJ2gSxpNc1ePGDnGhJlV%2FNvVqeoRdD9QE7Yl4iMdRuNmMHCQYLldtDvsze2JTJyPjNducSjejFfVsljaanFTcFx%2BMaI%2Fg%3D%3D%7Ctkp%3ABk9SR97647DcZw&LH_BIN=1)
  - $228.00

### Networking Hardware
- Managed switch with VLAN support (future addition)
- Patch panel (future addition)