document.addEventListener("DOMContentLoaded", () => {
  const select = document.querySelector("#sortSelect");
  const form = document.querySelector("#catalogForm");
  const pageInput = document.querySelector("#pageInput");
  const shell = document.querySelector(".shell");
  const catalog = document.querySelector("#catalog");
  const emptyState = document.querySelector("#emptyState");
  const skeleton = document.querySelector("#skeleton");
  const totalProducts = document.querySelector("#totalProducts");
  const rangeLabel = document.querySelector("#rangeLabel");
  const lastRefreshed = document.querySelector("#lastRefreshed");
  const apiStatus = document.querySelector("#apiStatus");
  const apiStatusValue = apiStatus ? apiStatus.querySelector(".status-value") : null;
  const apiLatency = document.querySelector("#apiLatency");
  const pager = document.querySelector(".pager");
  const prevBtn = document.querySelector("#prevPage");
  const nextBtn = document.querySelector("#nextPage");
  const currentPageLabel = document.querySelector("#currentPage");
  const totalPagesLabel = document.querySelector("#totalPages");
  const submitBtn = form ? form.querySelector("button[type='submit']") : null;
  const retryBtn = document.querySelector("#retryBtn");
  const endpointPreview = document.querySelector("#endpointPreview");
  const copyEndpointBtn = document.querySelector("#copyEndpoint");

  if (
    !select || !form || !pageInput || !shell || !catalog || !emptyState
    || !totalProducts || !rangeLabel || !lastRefreshed || !apiStatus || !apiStatusValue
    || !pager || !prevBtn || !nextBtn || !currentPageLabel || !totalPagesLabel
    || !retryBtn || !endpointPreview
  ) {
    return;
  }

  const pageSize = Math.max(1, Number(shell.dataset.pageSize) || 20);
  // The server already normalized `sort` and `page` and rendered the matching
  // <option>/data-initial-page via Thymeleaf, so the DOM is the source of truth.
  // We deliberately do NOT read URLSearchParams here — values like
  // ?page=99999999999999999999 parse as finite in JS (1e20) but are clamped to
  // 0 server-side, and trusting the raw URL would let the client fetch a page
  // the server has already rejected.
  const initialPage = Number(shell.dataset.initialPage);
  let currentPage = Number.isFinite(initialPage) && initialPage >= 0
    ? Math.floor(initialPage)
    : 0;
  pageInput.value = String(currentPage);

  const numberFmt = new Intl.NumberFormat("ko-KR");
  const timeFmt = new Intl.DateTimeFormat("ko-KR", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  });

  const buildApiUrl = (sort, page) =>
    `/api/products?sort=${encodeURIComponent(sort)}&page=${page}&size=${pageSize}`;

  const updateEndpointPreview = (sort, page) => {
    endpointPreview.textContent = buildApiUrl(sort, page);
  };

  const setStatus = (state, latencyMs) => {
    apiStatus.classList.remove("status-loading", "status-up", "status-degraded");
    if (state === "CHECKING") {
      apiStatus.classList.add("status-loading");
    } else if (state === "UP") {
      apiStatus.classList.add("status-up");
    } else {
      apiStatus.classList.add("status-degraded");
    }
    apiStatusValue.textContent = state;
    if (apiLatency) {
      if (Number.isFinite(latencyMs)) {
        apiLatency.textContent = `· ${Math.round(latencyMs)} ms`;
      } else {
        apiLatency.textContent = "";
      }
    }
  };

  const showSkeleton = (show) => {
    if (!skeleton) return;
    skeleton.hidden = !show;
  };

  const setEmpty = (title, detail, variant) => {
    showSkeleton(false);
    emptyState.hidden = false;
    emptyState.classList.toggle("error", variant === "error");
    emptyState.querySelector("strong").textContent = title;
    emptyState.querySelector("span").textContent = detail;
    retryBtn.hidden = variant !== "error";
  };

  const hideEmpty = () => {
    emptyState.hidden = true;
    emptyState.classList.remove("error");
    retryBtn.hidden = true;
  };

  const clearProducts = () => {
    catalog.querySelectorAll(".product-row").forEach((node) => node.remove());
  };

  const money = (value) => {
    if (value === null || value === undefined || value === "") {
      return "—";
    }
    const number = Number(value);
    if (!Number.isFinite(number)) {
      return "—";
    }
    return `${numberFmt.format(number)}원`;
  };

  const setControlsBusy = (busy) => {
    select.disabled = busy;
    if (submitBtn) submitBtn.disabled = busy;
    catalog.setAttribute("aria-busy", busy ? "true" : "false");
    if (busy) {
      prevBtn.disabled = true;
      nextBtn.disabled = true;
    }
  };

  // Block unreachable placeholder hosts (e.g. https://s3.local/...) used by
  // local smoke fixtures — those would otherwise trigger DNS errors in the
  // browser console when applied as background-image URLs.
  const isBrowserSafeThumbnailUrl = (raw) => {
    if (typeof raw !== "string" || raw === "") {
      return false;
    }
    let parsed;
    try {
      parsed = new URL(raw, window.location.origin);
    } catch (_) {
      return false;
    }
    if (parsed.protocol === "data:" || parsed.protocol === "blob:") {
      return true;
    }
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
      return false;
    }
    if (parsed.origin === window.location.origin) {
      return true;
    }
    const host = parsed.hostname.toLowerCase();
    if (host === "localhost" || host.endsWith(".local")) {
      return false;
    }
    return true;
  };

  const buildThumb = (product) => {
    const thumb = document.createElement("div");
    thumb.className = "thumb";
    const altLabel = [product.brandName, product.productName]
      .filter(Boolean)
      .join(" — ") || "Product image";

    if (isBrowserSafeThumbnailUrl(product.thumbnailUrl)) {
      const safeHref = new URL(product.thumbnailUrl, window.location.origin).href;
      const img = document.createElement("img");
      img.src = safeHref;
      img.alt = altLabel;
      img.loading = "lazy";
      img.decoding = "async";
      img.referrerPolicy = "no-referrer";
      img.addEventListener("error", () => {
        thumb.classList.add("empty");
        thumb.replaceChildren(document.createTextNode("No image"));
      }, { once: true });
      thumb.append(img);
    } else {
      thumb.classList.add("empty");
      thumb.append(document.createTextNode("No image"));
    }

    const discount = Number(product.discountRate || 0);
    if (discount > 0) {
      const badge = document.createElement("span");
      badge.className = "thumb-badge";
      badge.setAttribute("aria-label", `${discount} percent off`);
      badge.textContent = `-${discount}%`;
      thumb.append(badge);
    }
    return thumb;
  };

  const buildMain = (product) => {
    const main = document.createElement("div");
    main.className = "product-main";

    const brand = document.createElement("p");
    brand.className = "brand";
    brand.textContent = product.brandName || "Brand";

    const title = document.createElement("h2");
    if (product.productId !== undefined && product.productId !== null) {
      const idChip = document.createElement("span");
      idChip.className = "id-chip";
      idChip.textContent = `#${product.productId}`;
      title.append(idChip);
    }
    title.append(document.createTextNode(product.productName || "Untitled product"));

    main.append(brand, title);
    return main;
  };

  const buildMeta = (product) => {
    const meta = document.createElement("div");
    meta.className = "meta";

    const rating = document.createElement("span");
    rating.className = "rating";
    const ratingValue = Number(product.rating ?? 0);
    rating.textContent = Number.isFinite(ratingValue) ? ratingValue.toFixed(1) : "0.0";
    rating.setAttribute("aria-label", `Rating ${rating.textContent} out of 5`);

    const reviews = document.createElement("span");
    const reviewCount = Number(product.reviewCount ?? 0);
    reviews.textContent = `${numberFmt.format(reviewCount)} reviews`;

    meta.append(rating, reviews);
    return meta;
  };

  const buildPrice = (product) => {
    const price = document.createElement("div");
    price.className = "price-stack";

    const discount = Number(product.discountRate || 0);
    if (discount > 0) {
      const discountEl = document.createElement("span");
      discountEl.className = "discount";
      discountEl.textContent = `${discount}% OFF`;
      price.append(discountEl);
    }

    const sale = document.createElement("strong");
    sale.textContent = money(product.salePrice);
    sale.setAttribute("aria-label", `Sale price ${sale.textContent}`);
    price.append(sale);

    if (
      product.originalPrice !== null
      && product.originalPrice !== undefined
      && Number(product.originalPrice) !== Number(product.salePrice)
    ) {
      const original = document.createElement("span");
      original.className = "strike";
      original.textContent = money(product.originalPrice);
      original.setAttribute("aria-label", `Original price ${original.textContent}`);
      price.append(original);
    }
    return price;
  };

  const renderProducts = (products) => {
    clearProducts();
    if (products.length > 0) {
      hideEmpty();
    }

    const frag = document.createDocumentFragment();
    products.forEach((product) => {
      const row = document.createElement("article");
      row.className = "product-row";
      row.append(
        buildThumb(product),
        buildMain(product),
        buildMeta(product),
        buildPrice(product)
      );
      frag.append(row);
    });
    catalog.append(frag);
  };

  const updateRange = (totalElements, pageCount) => {
    if (!totalElements || pageCount === 0) {
      rangeLabel.textContent = "—";
      return;
    }
    const start = currentPage * pageSize + 1;
    const end = currentPage * pageSize + pageCount;
    rangeLabel.textContent = `${numberFmt.format(start)}–${numberFmt.format(end)}`;
  };

  const updatePager = (totalElements) => {
    const totalPages = Math.max(1, Math.ceil((totalElements || 0) / pageSize));
    currentPageLabel.textContent = numberFmt.format(currentPage + 1);
    totalPagesLabel.textContent = numberFmt.format(totalPages);
    prevBtn.disabled = currentPage <= 0;
    nextBtn.disabled = currentPage + 1 >= totalPages;
    pager.hidden = totalElements <= pageSize;
  };

  const syncUrl = () => {
    const next = new URLSearchParams();
    next.set("sort", select.value);
    next.set("page", String(currentPage));
    history.replaceState(null, "", `/products?${next.toString()}`);
  };

  const extractErrorMessage = async (response) => {
    try {
      const payload = await response.json();
      if (payload && payload.error) {
        const code = payload.error.code ? `${payload.error.code}: ` : "";
        if (payload.error.message) {
          return `${code}${payload.error.message}`;
        }
      }
    } catch (_) {
      // fall through to generic message
    }
    return `Product API returned ${response.status}`;
  };

  const loadProducts = async () => {
    const sort = select.value || "LATEST";
    pageInput.value = String(currentPage);
    updateEndpointPreview(sort, currentPage);
    setControlsBusy(true);
    setStatus("CHECKING");
    hideEmpty();
    showSkeleton(true);
    totalProducts.textContent = "0";
    rangeLabel.textContent = "—";
    clearProducts();

    const startedAt = performance.now();
    let response;
    try {
      response = await fetch(buildApiUrl(sort, currentPage), {
        headers: { Accept: "application/json" }
      });
    } catch (networkError) {
      setStatus("DEGRADED");
      setEmpty(
        "API unreachable",
        networkError.message || "Network error while contacting /api/products.",
        "error"
      );
      lastRefreshed.textContent = "—";
      pager.hidden = true;
      setControlsBusy(false);
      return;
    }

    const latencyMs = performance.now() - startedAt;

    if (!response.ok) {
      const detail = await extractErrorMessage(response);
      setStatus(`HTTP ${response.status}`, latencyMs);
      setEmpty("Request failed", detail, "error");
      lastRefreshed.textContent = "—";
      pager.hidden = true;
      setControlsBusy(false);
      return;
    }

    let payload;
    try {
      payload = await response.json();
    } catch (parseError) {
      setStatus("DEGRADED", latencyMs);
      setEmpty("Invalid response", "Product API returned a body that was not JSON.", "error");
      lastRefreshed.textContent = "—";
      pager.hidden = true;
      setControlsBusy(false);
      return;
    }

    const products = Array.isArray(payload.data) ? payload.data : [];
    const total = Number(payload.meta?.total ?? products.length) || 0;
    totalProducts.textContent = numberFmt.format(total);
    setStatus("UP", latencyMs);
    lastRefreshed.textContent = timeFmt.format(new Date());
    showSkeleton(false);
    renderProducts(products);
    updateRange(total, products.length);
    updatePager(total);
    if (products.length === 0) {
      setEmpty("No products", "Catalog data is empty.");
    }
    setControlsBusy(false);
  };

  select.addEventListener("change", () => {
    currentPage = 0;
    syncUrl();
    loadProducts();
  });

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    syncUrl();
    loadProducts();
  });

  retryBtn.addEventListener("click", () => {
    syncUrl();
    loadProducts();
  });

  if (copyEndpointBtn) {
    copyEndpointBtn.addEventListener("click", async () => {
      const url = endpointPreview.textContent || "";
      try {
        await navigator.clipboard.writeText(url);
        copyEndpointBtn.classList.add("copied");
        const previous = copyEndpointBtn.textContent;
        copyEndpointBtn.textContent = "Copied";
        setTimeout(() => {
          copyEndpointBtn.classList.remove("copied");
          copyEndpointBtn.textContent = previous;
        }, 1500);
      } catch (_) {
        // Clipboard blocked (e.g. insecure context). Select for manual copy.
        const range = document.createRange();
        range.selectNodeContents(endpointPreview);
        const sel = window.getSelection();
        if (sel) {
          sel.removeAllRanges();
          sel.addRange(range);
        }
      }
    });
  }

  const movePage = (delta) => {
    const target = currentPage + delta;
    if (target < 0) {
      return;
    }
    currentPage = target;
    syncUrl();
    loadProducts();
  };

  prevBtn.addEventListener("click", () => movePage(-1));
  nextBtn.addEventListener("click", () => movePage(1));

  updateEndpointPreview(select.value || "LATEST", currentPage);
  loadProducts();
});
