"use client";

import Image from "next/image";
import { useEffect, useState } from "react";

export type ExampleImage = {
  src: string;
  alt: string;
};

type ExampleSlideshowProps = {
  examples: ExampleImage[];
  intervalMs?: number;
};

type SlideshowState = {
  active: number;
  previous: number;
};

export function ExampleSlideshow({ examples, intervalMs = 3000 }: ExampleSlideshowProps) {
  const count = examples.length;
  const [state, setState] = useState<SlideshowState>({ active: 0, previous: 0 });

  useEffect(() => {
    if (count <= 1) return;

    const intervalId = window.setInterval(() => {
      setState((current) => ({
        active: (current.active + 1) % count,
        previous: current.active
      }));
    }, intervalMs);

    return () => window.clearInterval(intervalId);
  }, [count, intervalMs]);

  if (count === 0) {
    return <div className="example-empty" aria-hidden="true" />;
  }

  // The slides are absolutely stacked inside the same box, so every mounted
  // image is "in viewport" and lazy loading never defers it. Mount only the
  // previous (still fading out), current and next slides instead.
  const visible = new Set([
    state.previous % count,
    state.active % count,
    (state.active + 1) % count
  ]);

  return (
    <div className="example-slideshow">
      {examples.map((example, index) =>
        visible.has(index) ? (
          <Image
            key={example.src}
            src={example.src}
            alt={example.alt}
            fill
            sizes="(max-width: 900px) 92vw, 860px"
            priority={index === 0}
            className={`example-slide${index === state.active ? " is-active" : ""}`}
          />
        ) : null
      )}
    </div>
  );
}