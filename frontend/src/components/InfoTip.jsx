import { useId, useState } from 'react';

export default function InfoTip({ label, children }) {
  const id = useId();
  const [open, setOpen] = useState(false);
  return <span className="info-tip" onMouseEnter={() => setOpen(true)} onMouseLeave={() => setOpen(false)}>
    <button type="button" className="info-button" aria-label={`About ${label}`} aria-describedby={open ? id : undefined}
      onFocus={() => setOpen(true)} onBlur={() => setOpen(false)} onClick={() => setOpen(true)}
      onKeyDown={(event) => { if (event.key === 'Escape') { event.stopPropagation(); setOpen(false); } }}>i</button>
    {open && <span id={id} role="tooltip" className="info-popover">{children}</span>}
  </span>;
}
