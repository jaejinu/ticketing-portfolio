import { forwardRef, type SelectHTMLAttributes } from "react";
import { cn } from "./cn";
export interface SelectProps extends Omit<
  SelectHTMLAttributes<HTMLSelectElement>,
  "onChange"
> {
  options: { value: string; label: string }[];
  placeholder?: string;
  onChange: (value: string) => void;
}
export const Select = forwardRef<HTMLSelectElement, SelectProps>(
  function Select(
    { className, options, placeholder = "선택하세요", onChange, ...rest },
    ref,
  ) {
    return (
      <select
        {...rest}
        ref={ref}
        className={cn("ui-control", className)}
        onChange={(event) => onChange(event.target.value)}
      >
        <option value="" disabled>
          {placeholder}
        </option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    );
  },
);
