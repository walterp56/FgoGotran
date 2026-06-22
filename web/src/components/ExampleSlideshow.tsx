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

export function ExampleSlideshow({ examples, intervalMs = 3000 }: ExampleSlideshowProps) {
  const [activeIndex, setActiveIndex] = useState(0);

  useEffect(() => {
    if (examples.length <= 1) return;

    const intervalId = window.setInterval(() => {
      setActiveIndex((currentIndex) => (currentIndex + 1) % examples.length);
    }, intervalMs);

    return () => window.clearInterval(intervalId);
  }, [examples.length, intervalMs]);

  if (examples.length === 0) {
    return <div className="example-empty" aria-hidden="true" />;
  }

  return (
    <div className="example-slideshow">
      {examples.map((example, index) => (
        <Image
          key={example.src}
          src={example.src}
          alt={example.alt}
          width={2340}
          height={1080}
          priority={index === 0}
          className={`example-slide${index === activeIndex ? " is-active" : ""}`}
        />
      ))}
    </div>
  );
}
