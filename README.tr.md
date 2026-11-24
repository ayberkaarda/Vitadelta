# VitaDelta

English: [README.md](README.md)

VitaDelta, vitamin ve takviye karşılaştırma hizmetidir. Takviye ürünlerini, ülkeye göre referans besin değerlerini ne kadar karşıladıklarına bakarak karşılaştırır; üst sınırları ve etkin doz başına fiyatı da hesaba katar ve ürünleri şeffaf biçimde sıralar. Böylece kullanıcılar parasının karşılığında gerçekte ne aldığını görebilir. Puanlama deterministiktir: aynı girdi her zaman aynı sıralamayı üretir.

> VitaDelta yalnızca karşılaştırmalı bilgi sunar. Tıbbi tavsiye değildir ve bir hekim ya da eczacıyla yapılacak görüşmenin yerini tutmaz.

**Durum: Faz 0, iskelet.** Depo; tüm servislerin çalıştırılabilir iskeletlerini, sözleşmeleri, yerel ortamı ve CI iş akışını içerir. Ürün özellikleri sonraki fazlarda gelecektir.

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

## Testleri çalıştırma

```bash
scripts/test-all.sh
```

Betik, mevcut tüm test takımlarını (sözleşme lint'i, puanlama, API, web) çalıştırır, adım adım bir özet yazdırır ve herhangi bir adım başarısız olursa sıfırdan farklı bir kodla çıkar. Test takımları ayrı ayrı da çalıştırılabilir:

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
```

Mimari kararlar [docs/adr](docs/adr) altında kayıt altına alınır.

## Sürekli entegrasyon

Her push ve pull request, GitHub Actions üzerinde `scripts/test-all.sh` betiğini çalıştırır. Bir pull request yalnızca çalıştırma yeşilse birleştirilebilir.
