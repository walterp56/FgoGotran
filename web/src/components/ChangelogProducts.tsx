"use client";

import { Monitor, Smartphone } from "lucide-react";
import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";

type Product = "android" | "local";

type ChangelogProductsProps = {
  label: string;
  androidLabel: string;
  localLabel: string;
  android: ReactNode;
  local: ReactNode;
};

const products: Product[] = ["android", "local"];

export function ChangelogProducts({ label, androidLabel, localLabel, android, local }: ChangelogProductsProps) {
  const [active, setActive] = useState<Product>("android");
  const buttons = useRef<Partial<Record<Product, HTMLButtonElement | null>>>({});

  useEffect(() => {
    function readHash() {
      setActive(window.location.hash === "#local" ? "local" : "android");
    }
    readHash();
    window.addEventListener("hashchange", readHash);
    return () => window.removeEventListener("hashchange", readHash);
  }, []);

  function select(product: Product) {
    setActive(product);
    window.history.replaceState(window.history.state, "", `#${product}`);
  }

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>, product: Product) {
    let next: Product;
    if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
      next = products[(products.indexOf(product) + 1) % products.length];
    } else if (event.key === "Home") {
      next = "android";
    } else if (event.key === "End") {
      next = "local";
    } else {
      return;
    }
    event.preventDefault();
    select(next);
    buttons.current[next]?.focus();
  }

  return (
    <div className="changelog-products">
      <div className="changelog-tabs" role="tablist" aria-label={label}>
        {products.map((product) => {
          const Icon = product === "android" ? Smartphone : Monitor;
          return (
            <button
              type="button"
              role="tab"
              id={product}
              key={product}
              ref={(element) => { buttons.current[product] = element; }}
              aria-selected={active === product}
              aria-controls={`changelog-panel-${product}`}
              tabIndex={active === product ? 0 : -1}
              onClick={() => select(product)}
              onKeyDown={(event) => onKeyDown(event, product)}
            >
              <Icon size={18} aria-hidden="true" />
              <span>{product === "android" ? androidLabel : localLabel}</span>
            </button>
          );
        })}
      </div>
      {products.map((product) => (
        <div
          key={product}
          id={`changelog-panel-${product}`}
          role="tabpanel"
          aria-labelledby={product}
          hidden={active !== product}
          tabIndex={0}
        >
          {product === "android" ? android : local}
        </div>
      ))}
    </div>
  );
}
