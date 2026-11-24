# VitaDelta

English: [README.md](README.md)

VitaDelta, vitamin ve takviye karşılaştırma hizmetidir. Takviye ürünlerini, ülkeye göre referans besin değerlerini ne kadar karşıladıklarına bakarak karşılaştırır; üst sınırları ve etkin doz başına fiyatı da hesaba katar ve ürünleri şeffaf biçimde sıralar. Böylece kullanıcılar parasının karşılığında gerçekte ne aldığını görebilir. Puanlama deterministiktir: aynı girdi her zaman aynı sıralamayı üretir.

> VitaDelta yalnızca karşılaştırmalı bilgi sunar. Tıbbi tavsiye değildir ve bir hekim ya da eczacıyla yapılacak görüşmenin yerini tutmaz.

**Durum: Faz 4, katalog ve ürün uç noktaları, kayıt olmadan deneme sınırıyla sıralı öneriler, ikili karşılaştırma ve yönetici içe aktarmaları; temelde Faz 3 hesapları, planları ve promosyon kodları ile Faz 2 puanlama motoru (`/score` ve `/compare`, golden ve property testleri, karar kalibrasyonu) yer alır.** Depo; tüm servislerin çalıştırılabilir iskeletlerini, sözleşmeleri, yerel ortamı ve CI iş akışını, katalog veritabanı şemasını, referans verileri ve bunları yükleyen bir seed yükleyiciyi, ayrıca dahili `/score` ve `/compare` uç noktalarının arkasındaki puanlama motorunu içerir. Puanlama formülleri, yapılandırma ve hata modeli [services/scoring/README.md](services/scoring/README.md) dosyasında; karar kalibrasyonu sonucu [services/scoring/calibration/verdict-calibration.md](services/scoring/calibration/verdict-calibration.md) dosyasında yer alır. API artık kayıt, e-posta doğrulama, yenileme oturumlu giriş, parola sıfırlama, plan yetkileri ve promosyon kodlarını da sağlar. Katalog API üzerinden okunabilir (ülkeler, besinler, ürün arama ve ayrıntı), öneri sorguları puanlama servisinin puanladığı sıralı bir liste döndürür ve iki ürün ikili olarak karşılaştırılabilir. Kalan ürün özellikleri sonraki fazlarda gelecektir.

## Mimariye genel bakış

```
browser --> nginx (web, :8080, single public origin)
              |-- /      static Angular app
              '-- /api   --> api (Spring Boot) --> PostgreSQL
                                  |
                                  |--> scoring (FastAPI, internal only)
                                  '--> Mailpit (SMTP, local development)
```

| Bileşen | Teknoloji | Notlar |
|---|---|---|
| `apps/web` | Angular 22, nginx ile sunulur | Tek herkese açık origin; `/api` isteklerini API'ye yönlendirir |
| `services/api` | Spring Boot 4, Java 25, Gradle | Herkese açık REST API, yayınlanmış port yok |
| `services/scoring` | FastAPI, Python 3.14, uv | Saf puanlama motoru, yalnızca API'den erişilebilir |
| PostgreSQL 18 | Docker | Kalıcı adlandırılmış volume |
| Mailpit | Docker | Geliştirme ortamında giden e-postaları yakalar |

`contracts/` altındaki OpenAPI ve JSON Schema dosyaları esas alınır: web uygulaması, API ve puanlama servisi bu sözleşmelere göre geliştirilir ve sözleşme değişikliği önce `contracts/` içinde yapılır.

## Gereksinimler

- JDK 25 (Temurin önerilir), `JAVA_HOME` bu JDK'yı göstermelidir. `PATH` üzerinde daha yeni bir JDK bulunması yeterli değildir; Gradle `JAVA_HOME` değişkenini kullanır.
- Node.js 22.23 veya 22 serisindeki daha yeni bir sürüm, npm ile birlikte
- Python 3.14 ile [uv](https://docs.astral.sh/uv/) (`uv python install 3.14`)
- Compose v2 ile Docker
- Windows'ta Git Bash veya WSL; çünkü `scripts/` altındaki her şey POSIX `bash` betiğidir

## Hızlı başlangıç

```bash
scripts/dev-up.sh
```

İlk çalıştırmada betik, `.env.example` dosyasından `.env` oluşturur, geliştirme değerlerini doldurur ve rastgele yeni gizli anahtarlar üretir; ardından imajları derler, yığını başlatır ve tüm servisler sağlıklı olana kadar bekler. Son olarak aşağıdaki uç noktaları kontrol eder. Mevcut bir `.env` dosyasının üzerine asla yazılmaz. `.env` Git tarafından yok sayılır; asla commit etmeyin.

| Ne | URL |
|---|---|
| Web uygulaması | http://localhost:8080 |
| API sağlık kontrolü | http://localhost:8080/api/v1/health |
| Mailpit gelen kutusu | http://127.0.0.1:8025 |
| Mailpit SMTP | 127.0.0.1:1025 |
| PostgreSQL | 127.0.0.1:54320 (konteyner portu 5432; yerel bir PostgreSQL ile çakışmaması için ana makine portu 54320'dir) |

Kataloğu çalışan yığına şu komutla yükleyin:

```bash
scripts/seed.sh
```

Betik referans verileri, sentetik fixture'ları ve gerçek ABD etiket anlık görüntüsünü (sentetik demo fiyatlarla) yükler. Seed idempotenttir: `data/` altındaki dosyaları değiştirdikten sonra yeniden çalıştırın.

Yığını şu komutlarla durdurun:

```bash
scripts/dev-down.sh             # keeps the database volume
scripts/dev-down.sh --volumes   # also deletes the database volume (all local data is lost)
```

### POSIX kabuğu olmadan

Git Bash ya da WSL yoksa `.env.example` dosyasını kopyalayarak `.env` dosyasını kendiniz oluşturun ve her değişkene bir değer verin (gizli anahtarlar için uzun rastgele dizeler kullanın); ardından Compose'u doğrudan depo kök dizininden çalıştırın:

```bash
docker compose --env-file .env -f infra/docker-compose.yml up -d --build --wait
docker compose --env-file .env -f infra/docker-compose.yml down
```

Veritabanı volume'unu da silmek için `down` komutuna `--volumes` ekleyin.

## Geliştirmede hesaplar ve e-posta

API üzerinden kayıt olun (`POST /api/v1/auth/register`); doğrulama ve parola sıfırlama e-postalarını Mailpit yakalar ve http://127.0.0.1:8025 adresinden okunabilir. İlk yönetici, `.env` içindeki `VITADELTA_BOOTSTRAP_ADMIN_EMAIL` ve `VITADELTA_BOOTSTRAP_ADMIN_PASSWORD` değerlerinden oluşturulur; varsayılan kimlik bilgisi yoktur, bu yüzden API'yi başlatmadan önce ikisini de ayarlayın, aksi halde yönetici oluşturulmaz. İlk `scripts/dev-up.sh` çalıştırması tüm gizli anahtarları `.env` içine üretir. Actuator sağlık ve metrik uç noktaları, api konteynerinin içinde yayınlanmayan ve proxy'lenmeyen dahili bir portta sunulur; loglar yapılandırılmış JSON'dur ve kimlik bilgileri ile e-posta adresleri maskelenir.

## API'yi deneme

`scripts/dev-up.sh` ve `scripts/seed.sh` çalıştırıldıktan sonra API, nginx üzerinden http://localhost:8080 adresinde erişilebilir:

```bash
curl -s "http://localhost:8080/api/v1/countries"
curl -s "http://localhost:8080/api/v1/products?country=TR&q=gold"
curl -s -X POST "http://localhost:8080/api/v1/recommendations/query" \
  -H "Content-Type: application/json" \
  -d '{"country": "TR"}'
curl -s -X POST "http://localhost:8080/api/v1/recommendations/compare" \
  -H "Content-Type: application/json" \
  -d '{"country": "TR", "baselineProductId": "<id from the product search>", "candidateProductId": "<another id from the product search>"}'
curl -s "http://localhost:8080/api/v1/openapi.json"
```

Hesabı olmayan ziyaretçi üç ücretsiz öneri sorgusu yapabilir ve her sorgunun ilk üç sonucunu görür; dördüncü sorgu `quota_exceeded` ile yanıtlanır. Aynı anonim oturumu istekler arasında korumak için sorgu çağrısında `-c cookies.txt -b cookies.txt` kullanın. Çalışan API'nin OpenAPI belgesi `/api/v1/openapi.json` adresinden sunulur. Yöneticiler ürünleri (JSON) ve teklifleri (CSV) `/api/v1/admin/imports/*` üzerinden içe aktarır (ADMIN rolü); biçimler `contracts/schemas/` altında tanımlıdır. Kur modülü gelene kadar başka bir gösterim para birimiyle istenen fiyatlar kendi para biriminde gösterilir.

## Testleri çalıştırma

```bash
scripts/test-all.sh
```

Betik, mevcut tüm test takımlarını (sözleşme lint'i, puanlama, API, web) çalıştırır, adım adım bir özet yazdırır ve herhangi bir adım başarısız olursa sıfırdan farklı bir kodla çıkar. API test takımı PostgreSQL'i Testcontainers üzerinden başlatır, bu yüzden çalışan bir Docker daemon gerektirir. Test takımları ayrı ayrı da çalıştırılabilir:

```bash
# scoring (from services/scoring)
uv sync --locked
uv run --locked ruff check .
uv run --locked ruff format --check .
uv run --locked mypy .
uv run --locked pytest

# api (from services/api, with JAVA_HOME set to JDK 25)
./gradlew check

# web (from apps/web)
npm ci
npm run lint
npm test
npm run build
```

## Depo yapısı

```
apps/web/            Angular application
services/api/        Spring Boot API
services/scoring/    FastAPI scoring service
contracts/           OpenAPI and JSON Schema contracts
infra/               Docker Compose stack and nginx configuration
scripts/             dev-up.sh, dev-down.sh, test-all.sh
.github/             CI workflow and Dependabot configuration
data/                Reference data, synthetic fixtures and the real label snapshot (from Phase 1)
scripts/dsld/        Converter for the real label snapshot (from Phase 1)
```

Mimari kararlar [docs/adr](docs/adr) altında kayıt altına alınır.

## Veri kaynakları ve uyarılar

- **Sentetik fixture'lar.** Test ve gösterim fixture'ları kurgusal markalar kullanır ve sentetik olarak işaretlenir.
- **Gerçek ABD etiket anlık görüntüsü.** Faz 1'den itibaren depo, [NIH Dietary Supplement Label Database](https://dsld.od.nih.gov/) (DSLD) kaynağından genel API üzerinden alınmış gerçek ABD multivitamin ve mineral etiket verisinin bir anlık görüntüsünü de içerir. API belirtimi verinin CC0 1.0 olduğunu belirtir; sitenin kullanım koşulları sayfaları okunamadığı için lisans yalnızca API belirtiminden doğrulanmıştır. Bkz. [ADR-0012](docs/adr/0012-real-label-data-source.md).
- **İsimler yalnızca tanımlama içindir.** Marka ve ürün adları yalnızca ürünleri tanımlamak için kullanılır. VitaDelta markalarla ya da NIH ile bağlantılı değildir ve onlar tarafından onaylanmamıştır.
- **Canlı veri değil, anlık görüntü.** Etiket verisi, giriş tarihi gösterilen bir anlık görüntüdür; etiketler o tarihten sonra değişmiş olabilir.
- **Fiyatlar demo değerleridir.** Gerçek ürünlerin fiyatları uydurulmuş demo tekliflerdir ve hiçbir gerçek satıcıyı yansıtmaz.
- **Görsel ve logo yok.** Etiket görselleri ya da marka logoları kullanılmaz.
- **Tıbbi tavsiye değildir.** Karşılaştırma bilgilendirme amaçlıdır ve bir hekim ya da eczacının tavsiyesinin yerini tutmaz.

## Sürekli entegrasyon

Her push ve pull request, GitHub Actions üzerinde `scripts/test-all.sh` betiğini çalıştırır. Bir pull request yalnızca çalıştırma yeşilse birleştirilebilir.
