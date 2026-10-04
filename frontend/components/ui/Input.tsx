import { forwardRef, type InputHTMLAttributes } from "react";
import { cn } from "./cn";
export type InputProps = InputHTMLAttributes<HTMLInputElement>;
export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { className, type = "text", ...rest },
  ref,
) {
  return (
    <input
      {...rest}
      ref={ref}
      type={type}
      className={cn("ui-control", className)}
    />
  );
});
