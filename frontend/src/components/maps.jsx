import { useEffect, useMemo } from 'react';
import L from 'leaflet';
import { Circle, MapContainer, Marker, Popup, TileLayer, useMap, useMapEvents } from 'react-leaflet';
import 'leaflet/dist/leaflet.css';
import markerIcon from 'leaflet/dist/images/marker-icon.png';
import markerIcon2x from 'leaflet/dist/images/marker-icon-2x.png';
import markerShadow from 'leaflet/dist/images/marker-shadow.png';
import { CHENNAI } from '../utils/format';

// Bundlers break Leaflet's default marker image paths; point them at the imported files.
L.Icon.Default.mergeOptions({ iconUrl: markerIcon, iconRetinaUrl: markerIcon2x, shadowUrl: markerShadow });

const hospitalIcon = L.divIcon({
    className: '', iconSize: [30, 30], iconAnchor: [15, 15],
    html: '<div style="width:30px;height:30px;border-radius:8px;background:#d62839;color:#fff;display:flex;align-items:center;justify-content:center;font-weight:800;box-shadow:0 2px 6px rgba(0,0,0,.3)">H</div>',
});

function Tiles() {
    return <TileLayer url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" maxZoom={18}
                      attribution="&copy; OpenStreetMap contributors" />;
}

/** Zooms the map to show every point (or the circle) whenever they change. */
function FitTo({ points, circle }) {
    const map = useMap();
    useEffect(() => {
        if (circle) {
            map.fitBounds(L.latLng(circle.center).toBounds(circle.radiusKm * 2000), { padding: [10, 10] });
        } else if (points.length) {
            map.fitBounds(L.latLngBounds(points), { padding: [30, 30], maxZoom: 13 });
        }
    }, [map, JSON.stringify(points), circle?.radiusKm]); // eslint-disable-line react-hooks/exhaustive-deps
    return null;
}

/**
 * Hospitals as red "H" markers, with an optional search-radius circle around the first one.
 * hospitals: [{ id, name, latitude, longitude }]
 */
export function HospitalMap({ hospitals, radiusKm, tall }) {
    const points = useMemo(() => hospitals.map(h => [h.latitude, h.longitude]), [hospitals]);
    const circle = radiusKm && points.length ? { center: points[0], radiusKm } : null;
    return (
        <MapContainer className={`map ${tall ? 'tall' : ''}`} center={points[0] || CHENNAI} zoom={12} scrollWheelZoom={false}>
            <Tiles />
            {hospitals.map(h => (
                <Marker key={h.id} position={[h.latitude, h.longitude]} icon={hospitalIcon}>
                    <Popup>{h.name}</Popup>
                </Marker>
            ))}
            {circle && <Circle center={circle.center} radius={radiusKm * 1000}
                               pathOptions={{ color: '#d62839', weight: 1.5, fillOpacity: 0.07 }} />}
            <FitTo points={points} circle={circle} />
        </MapContainer>
    );
}

function ClickToPick({ onPick }) {
    useMapEvents({ click: (e) => onPick(e.latlng.lat, e.latlng.lng) });
    return null;
}

function Recenter({ position }) {
    const map = useMap();
    useEffect(() => { if (position) map.setView(position); }, [map, position?.[0], position?.[1]]); // eslint-disable-line react-hooks/exhaustive-deps
    return null;
}

/**
 * Click-to-pick location. value: { latitude, longitude } (strings), onChange(newValue).
 * Also shows the lat/lng inputs and a "use my location" button.
 */
export function LocationPicker({ value, onChange, onError }) {
    const lat = parseFloat(value.latitude);
    const lng = parseFloat(value.longitude);
    const position = !isNaN(lat) && !isNaN(lng) ? [lat, lng] : null;
    const set = (la, ln) => onChange({ latitude: la.toFixed(5), longitude: ln.toFixed(5) });

    const useGps = () => {
        if (!navigator.geolocation) return onError?.('Location is not available in this browser');
        navigator.geolocation.getCurrentPosition(
            p => set(p.coords.latitude, p.coords.longitude),
            () => onError?.('Could not get your location'));
    };

    return (
        <div>
            <MapContainer className="map picker" center={position || CHENNAI} zoom={11} scrollWheelZoom={false}>
                <Tiles />
                <ClickToPick onPick={set} />
                {position && <Marker position={position} />}
                <Recenter position={position} />
            </MapContainer>
            <div className="fields-2" style={{ marginTop: '.4rem' }}>
                <input name="latitude" placeholder="Latitude" required value={value.latitude}
                       onChange={e => onChange({ ...value, latitude: e.target.value })} />
                <input name="longitude" placeholder="Longitude" required value={value.longitude}
                       onChange={e => onChange({ ...value, longitude: e.target.value })} />
            </div>
            <div className="hint"><button type="button" className="linkbtn" onClick={useGps}>Use my current location</button></div>
        </div>
    );
}
